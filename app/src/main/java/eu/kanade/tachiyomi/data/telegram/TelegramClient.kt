package eu.kanade.tachiyomi.data.telegram

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import nyanime.telegram.TelegramAccount
import nyanime.telegram.TelegramAccountRepository
import nyanime.telegram.TelegramErrors
import nyanime.telegram.TelegramException
import nyanime.telegram.TelegramFailure
import nyanime.telegram.TelegramGateway
import nyanime.telegram.TelegramState
import nyanime.telegram.number
import nyanime.telegram.objectOrNull
import nyanime.telegram.tdRequest
import nyanime.telegram.telegramJson
import nyanime.telegram.text
import nyanime.telegram.type
import nyanime.telegram.withTelegramTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TelegramClient private constructor(private val context: Context) : TelegramGateway, TelegramAccountRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(TelegramState())
    override val state: StateFlow<TelegramState> = mutableState
    private val mutableUpdates = MutableSharedFlow<JsonObject>(extraBufferCapacity = 256)

    /** Ordered updates; repositories consume quickly and recover history after reconnection. */
    val updates = mutableUpdates.asSharedFlow()
    private val mutableSignedOut = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val signedOut = mutableSignedOut.asSharedFlow()
    private val lock = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    val receipts = MutableStateFlow<Map<Long, JsonObject>>(emptyMap())
    val files = MutableStateFlow<Map<Long, JsonObject>>(emptyMap())

    @Volatile private var service: Messenger? = null
    private var bound = false
    private var connected = CompletableDeferred<Unit>()
    private val events = Channel<String>(Channel.UNLIMITED)
    private val leases = ConcurrentHashMap.newKeySet<String>()

    private val chatSubscriptions = nyanime.telegram.TelegramChatSubscriptions()

    fun observeChats(owner: String, chats: Set<Long>) {
        val previous = chatSubscriptions.snapshot()
        if (chatSubscriptions.update(owner, chats) == previous) return
        publishObservedChats()
    }
    private fun publishObservedChats() {
        try {
            service?.send(
                Message.obtain(null, TelegramEngineService.OBSERVE_CHATS).apply {
                    replyTo = incoming
                    data = Bundle().apply {
                        putLongArray("chats", chatSubscriptions.snapshot().toLongArray())
                    }
                },
            )
        } catch (_: Exception) {
            disconnected()
        }
    }
    private val runtime = context.getSharedPreferences("telegram.runtime.v1", Context.MODE_PRIVATE)

    @Volatile private var retryUntil = runtime.getLong("retry_until", 0)
    fun retain(): String = UUID.randomUUID().toString().also(leases::add)
    fun release(token: String) {
        leases.remove(token)
        scope.launch {
            delay(30_000)
            closeIfUnused()
        }
    }
    private val incoming = Messenger(
        Handler(Looper.getMainLooper()) { msg ->
            msg.data.getString("json")?.let(events::trySend)
            true
        },
    )
    init {
        scope.launch {
            for (raw in events) {
                try {
                    accept(telegramJson.parseToJsonElement(raw).jsonObject)
                } catch (
                    cancel: CancellationException,
                ) {
                    throw cancel
                } catch (_: Exception) {
                    disconnected()
                }
            }
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            try {
                service = Messenger(binder)
                service?.send(Message.obtain(null, TelegramEngineService.ATTACH).apply { replyTo = incoming })
                publishObservedChats()
                if (!connected.isCompleted) connected.complete(Unit)
            } catch (_: Exception) {
                disconnected()
            }
        }
        override fun onServiceDisconnected(name: ComponentName) = disconnected()
        override fun onBindingDied(name: ComponentName) = disconnected()
        override fun onNullBinding(name: ComponentName) = disconnected()
    }

    private fun disconnected() {
        service = null
        mutableState.update {
            it.copy(
                authorization = "disconnected",
                connection = "",
                account = null,
                failure = TelegramFailure.DISCONNECTED,
            )
        }
        pending.values.forEach { it.completeExceptionally(TelegramException(TelegramFailure.DISCONNECTED)) }
        pending.clear()
        if (!connected.isCompleted) connected.completeExceptionally(TelegramException(TelegramFailure.DISCONNECTED))
    }

    override suspend fun connect() = lock.withLock {
        val attached = service
        if (attached != null) {
            if (mutableState.value.authorization == "disconnected") {
                // getMe can fail transiently while IPC remains alive. Re-read authorization
                // instead of keeping a failed account lookup permanently latched.
                mutableState.update { it.copy(authorization = "connecting", failure = null) }
                try {
                    attached.send(
                        Message.obtain(null, TelegramEngineService.REQUEST).apply {
                            data = Bundle().apply { putString("json", tdRequest("getAuthorizationState").toString()) }
                            replyTo = incoming
                        },
                    )
                } catch (_: Exception) {
                    disconnected()
                    throw TelegramException(TelegramFailure.DISCONNECTED)
                }
            }
            return@withLock
        }
        withContext(Dispatchers.Main) {
            if (bound) runCatching { context.unbindService(connection) }
            connected = CompletableDeferred()
            mutableState.update {
                it.copy(authorization = "connecting", connection = "", account = null, failure = null)
            }
            bound =
                context.bindService(
                    Intent(context, TelegramEngineService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            if (!bound) throw TelegramException(TelegramFailure.DISCONNECTED)
        }
        try {
            withTelegramTimeout(15_000) { connected.await() }
        } catch (failure: TelegramException) {
            disconnected()
            throw failure
        }
    }

    override suspend fun request(request: JsonObject): JsonObject {
        return requestCorrelated(request, UUID.randomUUID().toString())
    }
    private suspend fun requestCorrelated(request: JsonObject, id: String): JsonObject {
        val remaining = (retryUntil - System.currentTimeMillis() + 999) / 1000
        if (remaining > 0) throw TelegramException(TelegramFailure.RATE_LIMIT, remaining.coerceAtMost(86_400), 429)
        try {
            connect()
        } catch (failure: TelegramException) {
            throw TelegramException(
                failure.failure,
                failure.retryAfterSeconds,
                failure.code,
                failure.diagnostic,
                mayHaveBeenSent = false,
            )
        }
        val result = CompletableDeferred<JsonObject>()
        pending[id] = result
        try {
            val json = JsonObject(request + ("@extra" to JsonPrimitive(id))).toString()
            val destination = service ?: throw TelegramException(TelegramFailure.DISCONNECTED, mayHaveBeenSent = false)
            destination.send(
                Message.obtain(null, TelegramEngineService.REQUEST).apply {
                    data = Bundle().apply { putString("json", json) }
                    replyTo = incoming
                },
            )
            return withTelegramTimeout(45_000) { result.await() }
        } catch (failure: TelegramException) {
            android.util.Log.w(
                "NyanimeCloud",
                "Telegram method failed: ${request.type()} (${failure.failure}, code=${failure.code})",
            )
            throw failure
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun accept(event: JsonObject) {
        event.text("@extra").let { id ->
            pending.remove(id)?.let { result ->
                if (event.type() == "error") {
                    val failure = TelegramErrors.parse(event)
                    android.util.Log.w(
                        "NyanimeCloud",
                        "Telegram request failed: ${failure.failure} (code=${failure.code}, " +
                            "reason=${TelegramErrors.diagnostic(event)})",
                    )
                    val wait = failure.retryAfterSeconds
                    if (wait > 0) {
                        retryUntil = System.currentTimeMillis() + wait * 1000
                        runtime.edit().putLong("retry_until", retryUntil).apply()
                    }
                    result.completeExceptionally(failure)
                } else {
                    result.complete(event)
                }
            }
        }
        when (event.type()) {
            "nyanimeEngineFailure" -> {
                val failure = if (event.text("reason") ==
                    "configuration"
                ) {
                    TelegramFailure.CONFIGURATION
                } else {
                    TelegramFailure.NATIVE_UNAVAILABLE
                }
                mutableState.update { it.copy(authorization = "unavailable", failure = failure) }
                pending.values.forEach { it.completeExceptionally(TelegramException(failure)) }
                pending.clear()
            }
            "updateAuthorizationState" -> updateAuth(event.objectOrNull("authorization_state") ?: return)
            "updateConnectionState" -> mutableState.update {
                it.copy(connection = event.objectOrNull("state")?.type().orEmpty())
            }
            "updateMessageSendSucceeded", "updateMessageSendFailed" -> receipts.update {
                (
                    it +
                        (event.number("old_message_id") to event)
                    ).entries.toList().takeLast(100).associate { it.toPair() }
            }
            "updateFile" -> event.objectOrNull("file")?.let { file ->
                files.update {
                    (it + (file.number("id") to file)).entries.toList().takeLast(100).associate { it.toPair() }
                }
            }
            else -> if (event.type().startsWith("authorizationState")) updateAuth(event)
        }
        if (event.type().startsWith("update")) mutableUpdates.emit(event)
    }

    private fun updateAuth(auth: JsonObject) {
        val codeInfo = auth.objectOrNull("code_info")
        val resendAt = when {
            auth.type() == "authorizationStateWaitEmailCode" -> System.currentTimeMillis() + 60_000
            auth.type() == "authorizationStateWaitCode" && codeInfo?.objectOrNull("next_type") != null ->
                System.currentTimeMillis() + codeInfo.number("timeout").coerceIn(0, 3600) * 1000
            else -> 0
        }
        mutableState.update {
            it.copy(
                authorization = auth.type(),
                qrLink = auth.text("link"),
                hint = auth.text("password_hint"),
                failure = null,
                resendAt = resendAt,
                account = if (auth.type() == "authorizationStateReady") it.account else null,
            )
        }
        if (auth.type() == "authorizationStateReady") {
            scope.launch {
                try {
                    val me = request(tdRequest("getMe"))
                    require(me.number("id") > 0)
                    val account =
                        TelegramAccount(
                            me.number("id"),
                            (me.text("first_name") + " " + me.text("last_name")).trim(),
                            me.text("username"),
                        )
                    mutableState.update {
                        if (it.authorization ==
                            "authorizationStateReady"
                        ) {
                            it.copy(account = account)
                        } else {
                            it
                        }
                    }
                } catch (
                    cancel: CancellationException,
                ) {
                    throw cancel
                } catch (
                    _: Exception,
                ) {
                    mutableState.update {
                        it.copy(authorization = "disconnected", failure = TelegramFailure.DISCONNECTED)
                    }
                }
            }
        }
    }

    override suspend fun submit(value: String) {
        val request = when (state.value.authorization) {
            "authorizationStateWaitPhoneNumber" -> tdRequest(
                "setAuthenticationPhoneNumber",
                "phone_number" to JsonPrimitive(nyanime.telegram.TelegramPhoneNumbers.normalize(value)),
            )
            "authorizationStateWaitCode" -> tdRequest("checkAuthenticationCode", "code" to JsonPrimitive(value.trim()))
            "authorizationStateWaitPassword" -> tdRequest(
                "checkAuthenticationPassword",
                "password" to JsonPrimitive(value),
            )
            "authorizationStateWaitEmailAddress" -> tdRequest(
                "setAuthenticationEmailAddress",
                "email_address" to JsonPrimitive(value.trim()),
            )
            "authorizationStateWaitEmailCode" -> tdRequest(
                "checkAuthenticationEmailCode",
                "code" to tdRequest("emailAddressAuthenticationCode", "code" to JsonPrimitive(value.trim())),
            )
            else -> throw TelegramException(TelegramFailure.REQUEST)
        }
        request(request)
    }

    override suspend fun requestQr() {
        request(tdRequest("requestQrCodeAuthentication"))
    }
    override suspend fun logout() {
        val account = state.value.account?.id
        request(tdRequest("logOut"))
        withTimeout(30_000) { state.first { it.authorization == "authorizationStateClosed" } }
        account?.let { mutableSignedOut.emit(it) }
        close()
        withContext(Dispatchers.IO) { TelegramSessionKey.clear(context) }
        mutableState.value = TelegramState()
    }

    private suspend fun closeIfUnused() = lock.withLock { if (leases.isEmpty()) unbind() }

    /** Explicit recovery/logout resets IPC; ordinary consumers only release their own lease. */
    override suspend fun close() = lock.withLock { unbind() }
    private suspend fun unbind() {
        withContext(Dispatchers.Main) {
            runCatching {
                service?.send(Message.obtain(null, TelegramEngineService.DETACH).apply { replyTo = incoming })
            }
            if (bound) runCatching { context.unbindService(connection) }
            bound = false
            disconnected()
        }
    }

    companion object {
        @Volatile private var instance: TelegramClient? = null
        fun get(context: Context): TelegramClient = instance ?: synchronized(this) {
            instance ?: TelegramClient(context.applicationContext).also { instance = it }
        }
    }
}

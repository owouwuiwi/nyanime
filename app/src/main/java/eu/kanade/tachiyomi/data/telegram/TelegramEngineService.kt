package eu.kanade.tachiyomi.data.telegram

import android.app.Application
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import eu.kanade.tachiyomi.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import nyanime.telegram.number
import nyanime.telegram.objectOrNull
import nyanime.telegram.tdRequest
import nyanime.telegram.telegramJson
import nyanime.telegram.text
import nyanime.telegram.type
import org.drinkless.tdlib.JsonClient
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

/** The only process permitted to load TDLib. No player, extensions or application database is initialized here. */
class TelegramEngineService : Service() {
    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> fail("session") })
    private val peers = CopyOnWriteArraySet<Messenger>()

    @Volatile private var observedChats: Set<Long> = emptySet()

    private val initialized = CompletableDeferred<Int>()

    @Volatile private var clientId = 0

    @Volatile private var failureReason = "native"
    private val inbox = Messenger(
        Handler(Looper.getMainLooper()) { message ->
            when (message.what) {
                OBSERVE_CHATS -> {
                    if (message.replyTo in peers) {
                        observedChats = message.data.getLongArray("chats")
                            .let { it ?: longArrayOf() }.filter { it < 0 }.take(2048).toSet()
                    }
                }
                ATTACH -> {
                    message.replyTo?.let(peers::add)
                    scope.launch {
                        val id = initialized.await()
                        if (id == 0) {
                            broadcast(engineFailure())
                        } else {
                            JsonClient.send(id, tdRequest("getAuthorizationState").toString())
                        }
                    }
                }
                DETACH -> {
                    message.replyTo?.let(peers::remove)
                    if (peers.isEmpty()) stopSelf()
                }
                // Handler recycles Message as soon as this callback returns. Never retain it
                // (or its mutable Bundle) in asynchronous work: copy the immutable payload now.
                REQUEST -> message.data.getString("json")?.let { request ->
                    scope.launch {
                        val id = initialized.await()
                        if (id != 0 && request.length <= 128_000) {
                            JsonClient.send(id, request)
                        } else {
                            val extra = runCatching {
                                telegramJson.parseToJsonElement(request).jsonObject["@extra"]
                            }.getOrNull()
                            broadcast(
                                buildJsonObject {
                                    put("@type", "error")
                                    put("code", 503)
                                    put("message", "ENGINE_UNAVAILABLE")
                                    extra?.let { put("@extra", it) }
                                }.toString(),
                            )
                        }
                    }
                }
            }
            true
        },
    )

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            engineLifecycle.withLock {
                var closed = false
                var parametersSent = false
                try {
                    val silence = tdRequest(
                        "setLogStream",
                        "log_stream" to tdRequest("logStreamEmpty"),
                    )
                    JsonClient.execute(silence.toString())
                    JsonClient.execute(
                        tdRequest("setLogVerbosityLevel", "new_verbosity_level" to JsonPrimitive(0)).toString(),
                    )
                    val versionRequest = tdRequest("getOption", "name" to JsonPrimitive("version"))
                    val engineVersion = JsonClient.execute(versionRequest.toString())
                        ?.let { telegramJson.parseToJsonElement(it).jsonObject }
                    check(engineVersion?.type() == "optionValueString")
                    if (BuildConfig.TELEGRAM_API_ID <= 0 || BuildConfig.TELEGRAM_API_HASH.isBlank()) {
                        // The local JNI check requires no account or API request.
                        // Never log authentication data.
                        android.util.Log.i("NyanimeCloud", "Official TDLib JNI self-check passed")
                        fail("configuration")
                        return@withLock
                    }
                    val id = JsonClient.createClientId()
                    clientId = id
                    initialized.complete(id)
                    JsonClient.send(id, tdRequest("getAuthorizationState").toString())
                    while (isActive) {
                        val raw = JsonClient.receive(0.5) ?: continue
                        val event = telegramJson.parseToJsonElement(raw).jsonObject
                        if (event.number("@client_id") != id.toLong()) continue
                        if (event.type() == "error" && event.text("@extra") == "engine-parameters") {
                            fail("session")
                            break
                        }
                        val auth =
                            event.objectOrNull("authorization_state")
                                ?: event.takeIf { it.type().startsWith("authorizationState") }
                        if (auth?.type() == "authorizationStateWaitTdlibParameters" && !parametersSent) {
                            parametersSent = true
                            val directory = File(noBackupFilesDir, "telegram").apply { mkdirs() }
                            JsonClient.send(
                                id,
                                buildJsonObject {
                                    put("@type", "setTdlibParameters")
                                    put("database_directory", File(directory, "database").path)
                                    put("files_directory", File(directory, "files").path)
                                    put("database_encryption_key", TelegramSessionKey.get(this@TelegramEngineService))
                                    put("use_file_database", true)
                                    put("use_chat_info_database", true)
                                    put("use_message_database", true)
                                    put("use_secret_chats", false)
                                    put("api_id", BuildConfig.TELEGRAM_API_ID)
                                    put("api_hash", BuildConfig.TELEGRAM_API_HASH)
                                    put("system_language_code", resources.configuration.locales[0].language)
                                    put("device_model", "Nyanime · ${Build.MODEL}")
                                    put("system_version", Build.VERSION.RELEASE)
                                    put("application_version", BuildConfig.VERSION_NAME)
                                    put("@extra", "engine-parameters")
                                }.toString(),
                            )
                        }
                        // Do not broadcast unrelated personal messages to application features.
                        if (event["@extra"] != null ||
                            event.type() in EVENTS ||
                            auth != null ||
                            nyanime.telegram.TelegramUpdatePolicy.accepts(event, observedChats)
                        ) {
                            broadcast(raw)
                        }
                        if (auth?.type() == "authorizationStateClosed") {
                            closed = true
                            stopSelf()
                            break
                        }
                    }
                } catch (_: LinkageError) {
                    fail("native")
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    fail("session")
                } finally {
                    // JSON receive is process-wide. Drain closure before another instance can open the database.
                    if (!closed && clientId > 0) {
                        withContext(NonCancellable) {
                            runCatching {
                                JsonClient.send(clientId, tdRequest("close").toString())
                                val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
                                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                                    val raw = JsonClient.receive(0.5) ?: continue
                                    val event = telegramJson.parseToJsonElement(raw).jsonObject
                                    if (event.number("@client_id") == clientId.toLong() &&
                                        event.objectOrNull("authorization_state")?.type() == "authorizationStateClosed"
                                    ) {
                                        break
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun fail(reason: String) {
        failureReason = reason
        if (!initialized.isCompleted) initialized.complete(0)
        broadcast(engineFailure())
    }
    private fun engineFailure(): String = "{\"@type\":\"nyanimeEngineFailure\",\"reason\":\"$failureReason\"}"

    private fun broadcast(raw: String) {
        // Binder has a bounded transaction buffer. Fail oversized replies explicitly.
        // Never lose an acknowledgement silently.
        val safe = if (raw.toByteArray(Charsets.UTF_8).size > 450_000) {
            val extra = runCatching { telegramJson.parseToJsonElement(raw).jsonObject["@extra"] }.getOrNull()
            buildJsonObject {
                put("@type", "error")
                put("code", 413)
                put("message", "RESPONSE_TOO_LARGE")
                extra?.let { put("@extra", it) }
            }.toString()
        } else {
            raw
        }
        val bundle = Bundle().apply { putString("json", safe) }
        peers.forEach { peer ->
            try {
                peer.send(Message.obtain(null, EVENT).apply { data = bundle })
            } catch (
                _: Exception,
            ) {
                peers.remove(peer)
            }
        }
    }

    override fun onBind(intent: Intent): IBinder = inbox.binder

    override fun onDestroy() {
        runCatching {
            val id = clientId
            if (id != 0) JsonClient.send(id, tdRequest("close").toString())
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private val engineLifecycle = Mutex()
        const val ATTACH = 1
        const val DETACH = 2
        const val REQUEST = 3
        const val EVENT = 4
        const val OBSERVE_CHATS = 5
        private val EVENTS =
            setOf(
                "updateAuthorizationState",
                "updateConnectionState",
                "updateFile",
                "updateMessageSendSucceeded",
                "updateMessageSendFailed",
            )
        fun isEngineProcess(context: Context): Boolean {
            val name = if (Build.VERSION.SDK_INT >= 28) {
                Application.getProcessName()
            } else {
                val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                manager.runningAppProcesses?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName
            }
            return name == "${context.packageName}:telegram"
        }
    }
}

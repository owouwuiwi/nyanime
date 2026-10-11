package eu.kanade.tachiyomi.data.watch

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Outgoing TLS connections only: NAT traversal is unnecessary. Independent relays see random room
 * tags and short encrypted events, never a stream. Each connection has one bounded subscription.
 * All mutable network state and crypto run on the actor, away from the UI/player thread.
 */
class NostrWatchTransport(
    private val invite: WatchInvite,
    private val identity: WatchIdentity,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS).build(),
    private val wallMillis: () -> Long = System::currentTimeMillis,
) : WatchTransport {
    override val publicKey: String = identity.publicKey
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inbox = Channel<() -> Unit>(64)
    private val crypto = WatchCrypto(invite, identity)
    private val sockets = mutableMapOf<String, WebSocket>()
    private val probes = mutableMapOf<String, String>()
    private val connected = mutableSetOf<String>()
    private val disabled = mutableSetOf<String>()
    private val cooldownUntil = mutableMapOf<String, Long>()
    private val retries = mutableMapOf<String, Int>()
    private val retryJobs = mutableMapOf<String, Job>()
    private val seen = LinkedHashSet<String>()
    private val subscription = watchBase64(watchRandom(12))

    @Volatile private var closed = false

    @Volatile private var currentFailure = WatchRelayFailure.None
    private var onMessage: (String, WatchMessage) -> Unit = { _, _ -> }
    private var onConnection: (Int) -> Unit = {}
    internal var diagnostics: (String) -> Unit = {}

    override fun relayFailure(): WatchRelayFailure = currentFailure

    override fun start(onMessage: (String, WatchMessage) -> Unit, onConnection: (Int) -> Unit) {
        this.onMessage = onMessage
        this.onConnection = onConnection
        scope.launch {
            for (action in inbox) {
                if (closed) break
                runCatching(action)
            }
        }
        inbox.trySend { invite.relays.forEach(::connect) }
    }

    private fun connect(url: String) {
        if (closed || url in disabled || url in sockets) return
        val socket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    enqueue {
                        if (sockets[url] !== webSocket) return@enqueue
                        val subscribed = webSocket.send(
                            buildJsonArray {
                                add(JsonPrimitive("REQ"))
                                add(JsonPrimitive(subscription))
                                add(
                                    buildJsonObject {
                                        put("kinds", JsonArray(listOf(JsonPrimitive(WatchCrypto.KIND))))
                                        put("#x", JsonArray(listOf(JsonPrimitive(invite.topic))))
                                        put("limit", 0)
                                    },
                                )
                            }.toString(),
                        )
                        if (!subscribed) disconnected(url)
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.length > 40_960) return
                    enqueue {
                        if (sockets[url] !== webSocket) return@enqueue
                        receive(url, text)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    enqueue {
                        if (sockets[url] === webSocket) {
                            diagnostics("$url failure: ${t.javaClass.simpleName}; HTTP ${response?.code}")
                            disconnected(
                                url,
                                permanent = response?.code == 401 || response?.code == 403,
                                retryDelay = if (response?.code == 429) 60_000 else null,
                            )
                        }
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)
                    enqueue {
                        if (sockets[url] === webSocket) {
                            diagnostics("$url closed: $code ${reason.take(160)}")
                            disconnectForPolicy(url, reason)
                        }
                    }
                }
            },
        )
        sockets[url] = socket
        // A TCP/WebSocket connection alone does not prove that the subscription is usable.
        retryJobs[url]?.cancel()
        retryJobs[url] = scope.launch {
            delay(12_000)
            enqueue { if (sockets[url] === socket && url !in connected) disconnected(url) }
        }
    }

    private fun receive(url: String, text: String) {
        val array = watchJson.parseToJsonElement(text) as? JsonArray ?: return
        when (array.firstOrNull()?.jsonPrimitive?.content) {
            "EOSE" -> if (
                array.getOrNull(1)?.jsonPrimitive?.content == subscription &&
                url !in connected &&
                url !in probes
            ) {
                probe(url)
            }
            "CLOSED" -> {
                val reason = array.getOrNull(2)?.jsonPrimitive?.content.orEmpty()
                diagnostics("$url subscription closed: ${reason.take(160)}")
                disconnectForPolicy(url, reason)
            }
            "OK" -> {
                val eventId = array.getOrNull(1)?.jsonPrimitive?.content ?: return
                val accepted = array.getOrNull(2)?.jsonPrimitive?.booleanOrNull ?: return
                if (probes[url] == eventId) {
                    if (accepted) {
                        probes.remove(url)
                        retryJobs.remove(url)?.cancel()
                        retries[url] = 0
                        currentFailure = WatchRelayFailure.None
                        if (connected.add(url)) onConnection(connected.size)
                    } else {
                        reject(url, array.getOrNull(3)?.jsonPrimitive?.content.orEmpty())
                    }
                } else if (!accepted) {
                    val reason = array.getOrNull(3)?.jsonPrimitive?.content.orEmpty()
                    if (!reason.startsWith("duplicate:") && !reason.startsWith("mute:")) reject(url, reason)
                }
            }
            "EVENT" -> {
                if (array.size != 3 || array[1].jsonPrimitive.content != subscription) return
                val event = watchJson.decodeFromString<WatchEvent>(array[2].jsonObject.toString())
                if (event.pubkey == publicKey || event.id in seen) return
                val message = crypto.open(event, wallMillis()) ?: return
                if (message.command == "relay-check") return
                seen.add(event.id)
                if (seen.size > 512) seen.remove(seen.first())
                onMessage(event.pubkey, message)
            }
        }
    }

    /** EOSE proves only that reads work; a relay must accept an encrypted probe before it is usable. */
    private fun probe(url: String) {
        retryJobs.remove(url)?.cancel()
        val event = crypto.seal(
            WatchMessage(type = WatchMessageType.Ping, sequence = 1, at = wallMillis(), command = "relay-check"),
            wallMillis(),
        )
        probes[url] = event.id
        val payload = """["EVENT",""" + watchJson.encodeToString(event) + "]"
        if (sockets[url]?.send(payload) != true) {
            disconnected(url)
            return
        }
        retryJobs[url] = scope.launch {
            delay(8_000)
            enqueue { if (probes[url] == event.id) disconnected(url) }
        }
    }

    private fun reject(url: String, reason: String) {
        diagnostics("$url event rejected: ${reason.take(160)}")
        disconnectForPolicy(url, reason)
    }

    private fun disconnectForPolicy(url: String, reason: String) {
        val policy = reason.lowercase()
        val temporaryBan = policy.startsWith("banned:") && policy.contains("rate-limit")
        val permanent = !temporaryBan &&
            listOf("banned:", "blocked:", "pow:", "restricted:", "invalid:", "auth-required:")
                .any(policy::startsWith)
        val retryDelay = when {
            temporaryBan -> 5 * 60_000L
            policy.startsWith("rate-limited:") -> 60_000L
            else -> null
        }
        disconnected(url, permanent = permanent, retryDelay = retryDelay)
    }

    private fun disconnected(url: String, permanent: Boolean = false, retryDelay: Long? = null) {
        sockets.remove(url)?.cancel()
        probes.remove(url)
        connected.remove(url)
        retryJobs.remove(url)?.cancel()
        if (permanent) {
            disabled.add(url)
            refreshFailure()
            onConnection(connected.size)
            return
        }
        val attempt = (retries[url] ?: 0).coerceAtMost(5)
        retries[url] = attempt + 1
        val delayMillis = retryDelay ?: minOf(30_000L, 1000L shl attempt)
        if (retryDelay != null) cooldownUntil[url] = System.nanoTime() / 1_000_000 + retryDelay
        refreshFailure()
        onConnection(connected.size)
        retryJobs[url] = scope.launch {
            delay(delayMillis + Random.nextLong(500))
            enqueue { connect(url) }
        }
    }

    private fun refreshFailure() {
        val monotonicMillis = System.nanoTime() / 1_000_000
        currentFailure = when {
            connected.isNotEmpty() -> WatchRelayFailure.None
            invite.relays.all { it in disabled } -> WatchRelayFailure.Rejected
            invite.relays.all { it in disabled || (cooldownUntil[it] ?: 0L) > monotonicMillis } ->
                WatchRelayFailure.RateLimited
            else -> WatchRelayFailure.None
        }
    }

    override fun send(message: WatchMessage) {
        enqueue {
            if (connected.isEmpty()) return@enqueue
            val event = crypto.seal(message, wallMillis())
            val payload = """["EVENT",""" + watchJson.encodeToString(event) + "]"
            connected.toList().forEach { url ->
                if (sockets[url]?.send(payload) != true) disconnected(url)
            }
        }
    }

    override fun retryUnavailable() {
        enqueue {
            val monotonicMillis = System.nanoTime() / 1_000_000
            invite.relays.filter {
                it !in connected && it !in disabled && monotonicMillis >= (cooldownUntil[it] ?: 0L)
            }.forEach { url ->
                retryJobs.remove(url)?.cancel()
                sockets.remove(url)?.cancel()
                retries[url] = 0
                connect(url)
            }
        }
    }

    private fun enqueue(action: () -> Unit) {
        if (!closed) inbox.trySend(action)
    }

    override fun close() {
        if (closed) return
        // Preserve ordering so an explicit leave can reach the sockets before shutting them down.
        inbox.trySend {
            closed = true
            sockets.values.forEach { it.close(1000, null) }
            sockets.clear()
            identity.clear()
            inbox.close()
            scope.cancel()
            client.connectionPool.evictAll()
        }.also {
            if (it.isFailure) {
                closed = true
                scope.cancel()
                client.dispatcher.cancelAll()
                identity.clear()
            }
        }
    }
}

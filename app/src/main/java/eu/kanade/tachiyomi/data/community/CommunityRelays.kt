package eu.kanade.tachiyomi.data.community

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/** Bounded actor; an overloaded socket reconnects and recovers stored events, rather than losing them. */
internal class CommunityRelays(
    private val identity: CommunityIdentity,
    private val relays: List<String>,
    private val filters: () -> List<JsonObject>,
    private val received: suspend (NostrEvent) -> Unit,
    private val accepted: suspend (String, String) -> Unit,
    private val status: (Int, String?) -> Unit,
    private val next: suspend (String) -> NostrEvent? = { null },
    private val rejected: suspend (String, String, RelayRejection) -> Unit = { _, _, _ -> },
    private val authenticated: suspend (String) -> Unit = {},
    private val diagnostic: (String) -> Unit = {},
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS).pingInterval(25, TimeUnit.SECONDS).build(),
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<suspend () -> Unit>(256)
    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val recovering = AtomicBoolean(false)
    private val authentication = mutableMapOf<String, String>()
    private data class Page(
        var until: Long? = null,
        var oldest: Long = Long.MAX_VALUE,
        val ids: MutableSet<String> = mutableSetOf(),
        var limit: Int = 500,
        var subscription: String = "",
    )
    private val pages = mutableMapOf<Pair<String, String>, Page>()
    private var pageSequence = 0L
    private val ready = mutableSetOf<String>()
    private val retries = mutableMapOf<String, Int>()
    private val destinations = relays.toMutableSet()
    private val inboxes = mutableMapOf<String, List<String>>()
    private val queries = linkedMapOf<String, JsonObject>()
    private val direct = linkedMapOf<String, NostrEvent>()
    private val completedDirect = linkedSetOf<String>()
    private val directReceipts = mutableMapOf<String, MutableSet<String>>()
    private val directRetryAt = mutableMapOf<Pair<String, String>, Long>()
    private val nextSendAt = mutableMapOf<String, Long>()
    private val publishFailures = mutableMapOf<String, Int>()
    private val problems = linkedMapOf<String, String>()
    private val closedSubscriptions = linkedMapOf<Pair<String, String>, String>()
    private val diagnosticAt = mutableMapOf<String, Long>()

    @Volatile private var closed = false

    init {
        require(relays.size in 1..5 && relays.all(::validRelay))
        scope.launch {
            for (action in queue) {
                if (!closed) {
                    runCatching {
                        action()
                    }.onFailure { status(ready.size, "Risposta del relay non valida") }
                }
            }
        }
        enqueue { relays.forEach(::connect) }
        scope.launch {
            while (!closed) {
                delay(250)
                enqueue { pump() }
            }
        }
    }
    private fun enqueue(action: suspend () -> Unit) {
        if (!closed && queue.trySend(action).isFailure && recovering.compareAndSet(false, true)) {
            // Recover via stored-event subscriptions; do not keep growing memory under untrusted traffic.
            scope.launch {
                queue.send {
                    sockets.values.forEach { it.cancel() }
                    sockets.clear()
                    ready.clear()
                    pages.clear()
                    recovering.set(false)
                    destinations.forEach(::connect)
                }
            }
        }
    }
    private fun connect(url: String) {
        if (closed || sockets.containsKey(url)) return
        val socket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = enqueue {
                    if (sockets[url] ===
                        webSocket
                    ) {
                        subscribe(webSocket)
                        archive(url, webSocket, "private")
                        archive(url, webSocket, "sync")
                    }
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.length > 250_000) return
                    enqueue { if (sockets[url] === webSocket) receive(url, webSocket, text) }
                }
                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?,
                ) = enqueue {
                    reportDiagnostic(url, "connection", "${t.javaClass.simpleName} HTTP ${response?.code ?: 0}")
                    if (sockets[url] ===
                        webSocket
                    ) {
                        lost(url)
                    }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)
                    enqueue { if (sockets[url] === webSocket) lost(url) }
                }
            },
        )
        sockets[url] = socket
        scope.launch {
            delay(15_000)
            enqueue { if (sockets[url] === socket && url !in ready) lost(url) }
        }
    }
    private fun subscribe(socket: WebSocket) {
        filters().forEachIndexed { index, filter ->
            socket.send(JsonArray(listOf(JsonPrimitive("REQ"), JsonPrimitive("live-$index"), filter)).toString())
        }
        queries.forEach { (key, filter) ->
            socket.send(JsonArray(listOf(JsonPrimitive("REQ"), JsonPrimitive(key), filter)).toString())
        }
    }
    private fun archive(url: String, socket: WebSocket, channel: String) {
        val page = pages.getOrPut(url to channel) { Page() }
        if (page.subscription.isNotEmpty()) {
            socket.send(
                JsonArray(listOf(JsonPrimitive("CLOSE"), JsonPrimitive(page.subscription))).toString(),
            )
        }
        page.subscription = "archive-$channel-${++pageSequence}"
        page.ids.clear()
        page.oldest = Long.MAX_VALUE
        val filter = buildJsonObject {
            put("kinds", JsonArray(listOf(JsonPrimitive(if (channel == "private") 1059 else 30078))))
            put(if (channel == "private") "#p" else "authors", JsonArray(listOf(JsonPrimitive(identity.publicKey))))
            put("limit", page.limit)
            page.until?.let { put("until", it) }
        }
        socket.send(JsonArray(listOf(JsonPrimitive("REQ"), JsonPrimitive(page.subscription), filter)).toString())
    }
    private suspend fun receive(url: String, socket: WebSocket, text: String) {
        val message = communityJson.parseToJsonElement(text) as? JsonArray ?: return
        when (message.firstOrNull()?.jsonPrimitive?.content) {
            "AUTH" -> {
                val challenge = message.getOrNull(1)?.jsonPrimitive?.content ?: return
                if (challenge.length > 1024) return
                val auth = NostrEvent.create(
                    identity,
                    22242,
                    "",
                    listOf(listOf("relay", url), listOf("challenge", challenge)),
                )
                socket.send("[\"AUTH\"," + communityJson.encodeToString(auth) + "]")
                authentication[url] = auth.id
                scope.launch {
                    delay(15_000)
                    enqueue { if (authentication[url] == auth.id && sockets[url] === socket) lost(url) }
                }
            }
            "EOSE" -> {
                val subscription = message.getOrNull(1)?.jsonPrimitive?.content.orEmpty()
                closedSubscriptions.remove(url to subscription)
                if (subscription.startsWith("profile-") || subscription in listOf("search", "older", "sync-recovery")) {
                    socket.send(JsonArray(listOf(JsonPrimitive("CLOSE"), JsonPrimitive(subscription))).toString())
                }
                if (subscription.startsWith("live-")) {
                    ready.add(url)
                    retries[url] = 0
                    reportStatus()
                }
                if (subscription.startsWith("archive-")) {
                    val channel = subscription.removePrefix("archive-").substringBefore('-')
                    val page = pages[url to channel] ?: return
                    if (page.subscription != subscription) return
                    if (page.ids.isNotEmpty()) {
                        if (page.oldest == page.until) {
                            if (page.ids.size >= page.limit && page.limit < 4000) {
                                page.limit *= 2
                            } else {
                                if (channel == "private" && page.ids.size >= 20) {
                                    status(
                                        ready.size,
                                        "Il relay limita un intervallo dell’archivio. Aggiungi un altro relay per completare il recupero.",
                                    )
                                }
                                page.until = page.oldest - 1
                            }
                        } else {
                            page.until = page.oldest
                        }
                        if (requireNotNull(page.until) < 0) {
                            socket.send("[\"CLOSE\",\"$subscription\"]")
                            return
                        }
                        page.ids.clear()
                        page.oldest = Long.MAX_VALUE
                        archive(url, socket, channel)
                    } else {
                        socket.send("[\"CLOSE\",\"$subscription\"]")
                    }
                }
            }
            "EVENT" -> if (message.size == 3) {
                val event = communityJson.decodeFromString<NostrEvent>(message[2].toString())
                if (event.valid()) {
                    val subscription = message[1].jsonPrimitive.content
                    if (subscription.startsWith("archive-")) {
                        val channel = subscription.removePrefix("archive-").substringBefore('-')
                        val page = pages[url to channel] ?: return
                        if (page.subscription != subscription) return
                        page.ids.add(event.id)
                        page.oldest = minOf(page.oldest, event.created_at)
                    }
                    received(event)
                }
            }
            "OK" -> {
                val id = message.getOrNull(1)?.jsonPrimitive?.content ?: return
                val success = message.getOrNull(2)?.jsonPrimitive?.booleanOrNull == true
                val reason = RelayRejection.parse(message.getOrNull(3)?.jsonPrimitive?.content.orEmpty())
                if (!success) reportDiagnostic(url, "rejected", message.getOrNull(3)?.jsonPrimitive?.content.orEmpty())
                if (authentication[url] == id) {
                    authentication.remove(url)
                    if (success) {
                        authenticated(url)
                        nextSendAt.remove(url)
                        directRetryAt.keys.removeAll { it.second == url }
                        problems.remove(url)
                        closedSubscriptions.keys.removeAll { it.first == url }
                        subscribe(socket)
                        archive(url, socket, "private")
                        archive(url, socket, "sync")
                    } else {
                        // A relay with broken AUTH can still accept sync events. Keep writing,
                        // but never report its private inbox as fully operational.
                        closedSubscriptions[url to "authentication"] =
                            "${URI(
                                url,
                            ).host}: autenticazione rifiutata; la ricezione privata su questo relay è limitata."
                    }
                } else if (success) {
                    directReceipts.getOrPut(id) { mutableSetOf() }.add(url)
                    if (id !in direct) directReceipts.remove(id)
                    directRetryAt.remove(id to url)
                    accepted(id, url)
                    problems.remove(url)
                    publishFailures.remove(url)
                } else {
                    rejected(id, url, reason)
                    problems[url] = reason.describe(url)
                    val attempts = (publishFailures[url] ?: 0) + 1
                    publishFailures[url] = attempts
                    val retryAt = System.currentTimeMillis() + reason.retryDelay(attempts)
                    if (id in direct) directRetryAt[id to url] = retryAt
                    if (reason.pausesRelay) {
                        nextSendAt[url] = if (reason == RelayRejection.RateLimited) {
                            System.currentTimeMillis() + reason.baseDelay
                        } else {
                            retryAt
                        }
                    }
                }
                reportStatus()
            }
            "CLOSED" -> {
                val subscription = message.getOrNull(1)?.jsonPrimitive?.content.orEmpty()
                val detail = message.getOrNull(2)?.jsonPrimitive?.content.orEmpty()
                reportDiagnostic(url, "closed", detail)
                if (subscription.startsWith("live-") || subscription.startsWith("archive-")) {
                    closedSubscriptions[url to subscription] =
                        "${URI(
                            url,
                        ).host}: ricezione di alcuni aggiornamenti limitata. Verifica i relay nelle impostazioni."
                }
                reportStatus()
                // A private inbox relay may reject public discovery. Other subscriptions remain usable.
            }
            "NOTICE" -> reportDiagnostic(url, "notice", message.getOrNull(1)?.jsonPrimitive?.content.orEmpty())
        }
    }
    private fun reportDiagnostic(url: String, type: String, message: String) {
        val now = System.currentTimeMillis()
        val key = "$url:$type"
        if (now - (diagnosticAt[key] ?: 0) < 30_000) return
        diagnosticAt[key] = now
        diagnostic("relay=${URI(url).host} $type=${relayDiagnostic(message)}")
    }
    private fun lost(url: String) {
        sockets.remove(url)?.cancel()
        ready.remove(url)
        authentication.remove(url)
        closedSubscriptions.keys.removeAll { it.first == url }
        reportStatus()
        pages.keys.removeAll { it.first == url }
        val attempt = (retries[url] ?: 0).coerceAtMost(5)
        retries[url] = attempt + 1
        scope.launch {
            delay(minOf(30_000L, 1000L shl attempt) + Random.nextLong(500))
            enqueue { connect(url) }
        }
    }

    /** Only pairing uses this small volatile queue. Ordinary app data comes from the durable outbox. */
    fun send(events: List<NostrEvent>) = enqueue {
        events.forEach { event ->
            if (event.id !in completedDirect && (event.id in direct || direct.size < 256)) direct[event.id] = event
        }
        pump()
    }
    private fun targets(event: NostrEvent): List<String> {
        val recipient = event.takeIf { it.kind == 1059 }?.tag("p")
        return if (recipient != null && recipient != identity.publicKey) {
            inboxes[recipient].orEmpty().ifEmpty { relays }
        } else {
            relays
        }
    }
    private suspend fun pump() {
        val now = System.currentTimeMillis()
        direct.values.filter { event ->
            event.tag("expiration")?.toLongOrNull()?.let { it * 1000 <= now } == true ||
                directReceipts[event.id].orEmpty().containsAll(targets(event))
        }.map { it.id }.forEach { id ->
            direct.remove(id)
            completedDirect.add(id)
            while (completedDirect.size > 512) completedDirect.remove(completedDirect.first())
            directReceipts.remove(id)
            directRetryAt.keys.removeAll { it.first == id }
        }
        for (url in ready.toList()) {
            if (authentication.containsKey(url) || now < (nextSendAt[url] ?: 0)) continue
            val event = direct.values.firstOrNull {
                url in targets(it) &&
                    url !in directReceipts[it.id].orEmpty() &&
                    now >= (directRetryAt[it.id to url] ?: 0)
            } ?: next(url) ?: continue
            if (url !in targets(event)) continue
            nextSendAt[url] = System.currentTimeMillis() + RelayRejection.SEND_INTERVAL
            if (event.id in direct) directRetryAt[event.id to url] = now + RelayRejection.ACK_TIMEOUT
            val payload = "[\"EVENT\"," + communityJson.encodeToString(event) + "]"
            if (sockets[url]?.send(payload) != true) lost(url)
        }
    }
    private fun reportStatus() = status(
        ready.size,
        closedSubscriptions.values.firstOrNull() ?: problems.values.firstOrNull(),
    )
    fun addDestinations(values: List<String>, peer: String = "") = enqueue {
        val valid = values.filter(::validRelay).distinct().take(5)
        if (validKey(peer) && valid.isNotEmpty()) inboxes[peer] = valid
        valid.forEach { url ->
            if (destinations.size < 64 || url in destinations) {
                destinations.add(url)
                connect(url)
            } else {
                status(ready.size, "Troppi relay diversi: aggiungi tra i tuoi relay quello del destinatario.")
            }
        }
    }
    fun query(key: String) = enqueue {
        require(validKey(key))
        val filter = buildJsonObject {
            put("authors", JsonArray(listOf(JsonPrimitive(key))))
            put("kinds", JsonArray(listOf(0, 30078, 10002, 10050).map(::JsonPrimitive)))
            put("limit", 300)
        }
        val subscription = profileSubscription(key)
        queries[subscription] = filter
        // Leave room for live/archive requests on relays with a small subscription budget.
        while (queries.size > 8) {
            val removed = queries.keys.first()
            queries.remove(removed)
            sockets.values.forEach { it.send("[\"CLOSE\",\"$removed\"]") }
        }
        sockets.values.forEach {
            it.send(JsonArray(listOf(JsonPrimitive("REQ"), JsonPrimitive(subscription), filter)).toString())
        }
    }
    fun search(name: String) = enqueue {
        val filter =
            buildJsonObject {
                put("kinds", JsonArray(listOf(JsonPrimitive(0))))
                put("search", name.take(60))
                put("limit", 30)
            }
        sockets.values.forEach {
            it.send(JsonArray(listOf(JsonPrimitive("REQ"), JsonPrimitive("search"), filter)).toString())
        }
    }
    fun recover(addresses: List<String>) = enqueue {
        if (addresses.isEmpty()) return@enqueue
        val filter = buildJsonObject {
            put("authors", JsonArray(listOf(JsonPrimitive(identity.publicKey))))
            put("kinds", JsonArray(listOf(JsonPrimitive(30078))))
            put("#d", JsonArray(addresses.take(40).map(::JsonPrimitive)))
            put("limit", 40)
        }
        queries["sync-recovery"] = filter
        sockets.values.forEach {
            it.send(JsonArray(listOf(JsonPrimitive("REQ"), JsonPrimitive("sync-recovery"), filter)).toString())
        }
    }
    fun older(until: Long) = enqueue {
        val history = filters().map { JsonObject(it + ("until" to JsonPrimitive(until))) }
        sockets.values.forEach {
            it.send(JsonArray(listOf(JsonPrimitive("REQ"), JsonPrimitive("older")) + history).toString())
        }
    }
    override fun close() {
        closed = true
        sockets.values.forEach { it.cancel() }
        sockets.clear()
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        queue.close()
        scope.cancel()
    }
    companion object {
        // NIP-01 subscription IDs must fit in 64 characters, including our prefix.
        internal fun profileSubscription(key: String) = "profile-${sha256(key.toByteArray()).hex().take(32)}"
        val defaults = listOf("wss://relay.damus.io", "wss://nos.lol")
        fun validRelay(value: String): Boolean = runCatching {
            val uri = URI(value)
            uri.scheme == "wss" &&
                uri.host != null &&
                uri.rawUserInfo == null &&
                uri.rawFragment == null &&
                (uri.port == -1 || uri.port in 1..65535) &&
                value.length <= 256
        }.getOrDefault(false)
    }
}

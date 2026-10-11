package eu.kanade.tachiyomi.data.community

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap

class CommunityRelayTest {
    @Test
    fun `private envelopes use recipient inbox without spreading self sync to it`() = runBlocking {
        val certificate = HeldCertificate.Builder().commonName(
            "localhost",
        ).addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(
            certificate,
        ).addTrustedCertificate(certificate.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(tls.sslSocketFactory(), tls.trustManager).build()
        val ready = Channel<Unit>(Channel.CONFLATED)
        val arrivals = Channel<Pair<String, String>>(Channel.UNLIMITED)
        fun relay(label: String) = MockWebServer().apply {
            useHttps(tls.sslSocketFactory())
            enqueue(
                MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val message = communityJson.parseToJsonElement(text) as JsonArray
                        when (message[0].jsonPrimitive.content) {
                            "REQ" -> webSocket.send("[\"EOSE\",${message[1]}]")
                            "EVENT" -> {
                                val event = communityJson.decodeFromString<NostrEvent>(message[1].toString())
                                arrivals.trySend(label to event.id)
                            }
                        }
                    }
                }).build(),
            )
            start()
        }
        CommunityIdentity().use { identity ->
            CommunityIdentity().use { peer ->
                relay("own").use { ownRelay ->
                    relay("peer").use { peerRelay ->
                        fun MockWebServer.wss() = url("/").toString().replace("https://", "wss://")
                        CommunityRelays(
                            identity,
                            listOf(ownRelay.wss()),
                            filters = { listOf(buildJsonObject { put("limit", 1) }) },
                            received = {},
                            accepted = { _, _ -> },
                            status = { count, _ -> if (count == 2) ready.trySend(Unit) },
                            client = client,
                        ).use { transport ->
                            transport.addDestinations(listOf(peerRelay.wss()), peer.publicKey)
                            withTimeout(10_000) { ready.receive() }
                            val rumor = GiftWrap.rumor(identity.publicKey, 14, "Encrypted message")
                            val private = GiftWrap.wrap(identity, peer.publicKey, rumor)
                            val self = GiftWrap.wrap(identity, identity.publicKey, rumor)
                            val sync = NostrEvent.create(identity, 30078, "Encrypted sync fixture")
                            transport.send(listOf(private, self, sync))
                            val delivered = withTimeout(10_000) { (1..3).map { arrivals.receive() }.toSet() }
                            assertEquals(setOf("peer" to private.id, "own" to self.id, "own" to sync.id), delivered)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `authenticated TLS relay retains concurrent profile queries and acknowledges sends`() = runBlocking {
        val certificate = HeldCertificate.Builder().commonName(
            "localhost",
        ).addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(
            clientTls.sslSocketFactory(),
            clientTls.trustManager,
        ).build()
        val queries = ConcurrentHashMap.newKeySet<String>()
        val ready = Channel<Unit>(Channel.CONFLATED)
        val accepted = Channel<String>(Channel.UNLIMITED)
        val received = Channel<NostrEvent>(Channel.UNLIMITED)
        val connection = Channel<WebSocket>(1)
        val authenticated = Channel<NostrEvent>(1)
        val archived = Channel<NostrEvent>(Channel.UNLIMITED)
        CommunityIdentity().use { identity ->
            val archiveEvents = (1..3).map {
                NostrEvent.create(
                    identity,
                    30078,
                    "Encrypted fixture $it",
                    at =
                    System.currentTimeMillis() / 1000 - it * 10,
                )
            }
            MockWebServer().use { server ->
                server.useHttps(serverTls.sslSocketFactory())
                server.enqueue(
                    MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            connection.trySend(webSocket)
                            webSocket.send("[\"AUTH\",\"one-time-challenge\"]")
                        }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val message = communityJson.parseToJsonElement(text) as JsonArray
                            when (message[0].jsonPrimitive.content) {
                                "AUTH" -> {
                                    val event = communityJson.decodeFromString<NostrEvent>(message[1].toString())
                                    authenticated.trySend(event)
                                    webSocket.send("[\"OK\",\"${event.id}\",true,\"\"]")
                                }
                                "REQ" -> {
                                    val subscription = message[1].jsonPrimitive.content
                                    if (subscription.length > 64) {
                                        webSocket.send("[\"CLOSED\",${message[1]},\"invalid: subscription too long\"]")
                                        return
                                    }
                                    queries.add(subscription)
                                    if (subscription.startsWith("archive-sync-")) {
                                        val until =
                                            message[2].jsonObject["until"]?.jsonPrimitive?.longOrNull ?: Long.MAX_VALUE
                                        archiveEvents.filter { it.created_at <= until }.take(1).forEach { event ->
                                            webSocket.send(
                                                "[\"EVENT\",\"$subscription\",${communityJson.encodeToString(event)}]",
                                            )
                                        }
                                    }
                                    // Public discovery can be unavailable while the private inbox still works.
                                    val reply = if (subscription == "live-0") "CLOSED" else "EOSE"
                                    webSocket.send(
                                        JsonArray(listOf(JsonPrimitive(reply), JsonPrimitive(subscription))).toString(),
                                    )
                                }
                                "EVENT" -> {
                                    val event = communityJson.decodeFromString<NostrEvent>(message[1].toString())
                                    webSocket.send("[\"OK\",\"${event.id}\",true,\"\"]")
                                }
                            }
                        }
                    }).build(),
                )
                server.start()
                val url = server.url("/").toString().replace("https://", "wss://")
                CommunityRelays(
                    identity,
                    listOf(url),
                    filters = { listOf(buildJsonObject { put("limit", 1) }, buildJsonObject { put("limit", 2) }) },
                    received = { if (it.kind == 30078) archived.send(it) else received.send(it) },
                    accepted = { id, _ -> accepted.send(id) },
                    status = { count, _ -> if (count > 0) ready.trySend(Unit) },
                    client = client,
                ).use { transport ->
                    val first = "a".repeat(64)
                    val second = "b".repeat(64)
                    transport.query(first)
                    transport.query(second)
                    withTimeout(10_000) { ready.receive() }
                    val auth = withTimeout(10_000) { authenticated.receive() }
                    assertTrue(auth.valid())
                    assertEquals("one-time-challenge", auth.tag("challenge"))
                    assertEquals(url, auth.tag("relay"))
                    val event = NostrEvent.create(identity, 1, "Local test event")
                    transport.send(listOf(event))
                    assertEquals(event.id, withTimeout(10_000) { accepted.receive() })
                    val socket = withTimeout(10_000) { connection.receive() }
                    socket.send(
                        "[\"EVENT\",\"live-1\",${communityJson.encodeToString(event.copy(content = "tampered"))}]",
                    )
                    socket.send("[\"EVENT\",\"live-1\",${communityJson.encodeToString(event)}]")
                    assertEquals(event, withTimeout(10_000) { received.receive() })
                    assertTrue(queries.contains(CommunityRelays.profileSubscription(first)))
                    assertTrue(queries.contains(CommunityRelays.profileSubscription(second)))
                    val restored = mutableSetOf<String>()
                    withTimeout(10_000) { while (restored.size < 3) restored.add(archived.receive().id) }
                    assertEquals(archiveEvents.map { it.id }.toSet(), restored)
                }
            }
        }
    }
}

package eu.kanade.tachiyomi.data.community

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

class CommunityDeliveryTest {
    private val certificate = HeldCertificate.Builder().commonName(
        "localhost",
    ).addSubjectAlternativeName("localhost").build()
    private val tls = HandshakeCertificates.Builder().heldCertificate(
        certificate,
    ).addTrustedCertificate(certificate.certificate).build()
    private val client = OkHttpClient.Builder().sslSocketFactory(tls.sslSocketFactory(), tls.trustManager).build()

    private fun relay(receive: (WebSocket, JsonArray) -> Unit) = MockWebServer().apply {
        useHttps(tls.sslSocketFactory())
        enqueue(
            MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = communityJson.parseToJsonElement(text) as JsonArray
                    if (message[0].jsonPrimitive.content == "REQ") {
                        webSocket.send("[\"EOSE\",${message[1]}]")
                    } else {
                        receive(webSocket, message)
                    }
                }
            }).build(),
        )
        start()
    }

    private fun MockWebServer.wss() = url("/").toString().replace("https://", "wss://")

    @Test
    fun `broken relay authentication does not stop accepted writes or hide inbox failure`() = runBlocking {
        val ack = Channel<String>(Channel.UNLIMITED)
        val issues = CopyOnWriteArrayList<String>()
        var sent = 0
        CommunityIdentity().use { identity ->
            relay { socket, message ->
                if (message[0].jsonPrimitive.content !in listOf("AUTH", "EVENT")) return@relay
                val event = communityJson.decodeFromString<NostrEvent>(message[1].toString())
                when (message[0].jsonPrimitive.content) {
                    "AUTH" -> socket.send("[\"OK\",\"${event.id}\",false,\"error: relay configuration incomplete\"]")
                    "EVENT" -> {
                        if (sent++ == 0) {
                            socket.send("[\"CLOSED\",\"live-1\",\"auth-required: private inbox\"]")
                            socket.send("[\"AUTH\",\"fixture-challenge\"]")
                        }
                        socket.send("[\"OK\",\"${event.id}\",true,\"\"]")
                    }
                }
            }.use { server ->
                CommunityRelays(
                    identity,
                    listOf(server.wss()),
                    filters = { listOf(buildJsonObject { put("limit", 1) }) },
                    received = {},
                    accepted = { id, _ -> ack.send(id) },
                    status = { _, issue -> issue?.let(issues::add) },
                    client = client,
                ).use { transport ->
                    val events = (1..2).map { NostrEvent.create(identity, 30078, "Encrypted fixture $it") }
                    transport.send(events)
                    assertEquals(
                        events.map {
                            it.id
                        }.toSet(),
                        withTimeout(10_000) { (1..2).map { ack.receive() }.toSet() },
                    )
                    assertTrue(issues.any { it.contains("ricezione") })
                }
            }
        }
    }

    @Test
    fun `rate limited relay rests while healthy relay drains without duplicate resends`() = runBlocking {
        val blocked = CopyOnWriteArrayList<String>()
        val healthy = CopyOnWriteArrayList<Pair<String, Long>>()
        val ack = Channel<String>(Channel.UNLIMITED)
        val rejection = Channel<RelayRejection>(Channel.UNLIMITED)
        CommunityIdentity().use { identity ->
            relay { socket, message ->
                if (message[0].jsonPrimitive.content == "EVENT") {
                    val event = communityJson.decodeFromString<NostrEvent>(message[1].toString())
                    blocked.add(event.id)
                    socket.send("[\"OK\",\"${event.id}\",false,\"rate-limited: slow down\"]")
                }
            }.use { limited ->
                relay { socket, message ->
                    if (message[0].jsonPrimitive.content == "EVENT") {
                        val event = communityJson.decodeFromString<NostrEvent>(message[1].toString())
                        healthy.add(event.id to System.nanoTime())
                        socket.send("[\"OK\",\"${event.id}\",true,\"\"]")
                    }
                }.use { open ->
                    CommunityRelays(
                        identity,
                        listOf(limited.wss(), open.wss()),
                        filters = { listOf(buildJsonObject { put("limit", 1) }) },
                        received = {},
                        accepted = { id, _ -> ack.send(id) },
                        status = { _, _ -> },
                        rejected = { _, _, reason -> rejection.send(reason) },
                        client = client,
                    ).use { transport ->
                        val events = (1..3).map { NostrEvent.create(identity, 1, "Local fixture $it") }
                        transport.send(events)
                        assertEquals(RelayRejection.RateLimited, withTimeout(10_000) { rejection.receive() })
                        assertEquals(
                            events.map {
                                it.id
                            }.toSet(),
                            withTimeout(10_000) { (1..3).map { ack.receive() }.toSet() },
                        )
                        transport.send(events)
                        delay(1200)
                        assertEquals(1, blocked.size)
                        assertEquals(3, healthy.size)
                        assertTrue(healthy.zipWithNext().all { (a, b) -> (b.second - a.second) / 1_000_000 >= 480 })
                    }
                }
            }
        }
    }

    @Test
    fun `rejected send resumes after successful authentication`() = runBlocking {
        val ack = Channel<String>(Channel.UNLIMITED)
        var attempts = 0
        CommunityIdentity().use { identity ->
            relay { socket, message ->
                when (message[0].jsonPrimitive.content) {
                    "AUTH" -> {
                        val event = communityJson.decodeFromString<NostrEvent>(message[1].toString())
                        socket.send("[\"OK\",\"${event.id}\",true,\"\"]")
                    }
                    "EVENT" -> {
                        val event = communityJson.decodeFromString<NostrEvent>(message[1].toString())
                        attempts++
                        if (attempts == 1) {
                            socket.send("[\"OK\",\"${event.id}\",false,\"auth-required: authenticate\"]")
                            socket.send("[\"AUTH\",\"fixture-challenge\"]")
                        } else {
                            socket.send("[\"OK\",\"${event.id}\",true,\"\"]")
                        }
                    }
                }
            }.use { server ->
                CommunityRelays(
                    identity,
                    listOf(server.wss()),
                    filters = { listOf(buildJsonObject { put("limit", 1) }) },
                    received = {},
                    accepted = { id, _ -> ack.send(id) },
                    status = { _, _ -> },
                    client = client,
                ).use { transport ->
                    val event = NostrEvent.create(identity, 1, "Local fixture")
                    transport.send(listOf(event))
                    assertEquals(event.id, withTimeout(10_000) { ack.receive() })
                    assertEquals(2, attempts)
                }
            }
        }
    }
}

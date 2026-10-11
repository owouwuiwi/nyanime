package eu.kanade.tachiyomi.data.watch

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class WatchRelayHealthTest {
    @Test
    fun subscriptionIsNotUsableUntilTheRelayAcceptsAPublishedProbe() {
        relay { server, client, events ->
            val identity = WatchIdentity()
            val transport = transport(server, client, identity)
            val connections = LinkedBlockingQueue<Int>()
            try {
                transport.start({ _, _ -> }, connections::add)
                val probe = events.poll(5, TimeUnit.SECONDS)
                assertNotNull(probe)
                assertNull(connections.poll(100, TimeUnit.MILLISECONDS))
                probe.socket.send("[\"OK\",\"${probe.id}\",true,\"\"]")
                assertEquals(1, connections.poll(5, TimeUnit.SECONDS))

                transport.send(WatchMessage(type = WatchMessageType.Status, sequence = 2, at = 1))
                val message = events.poll(5, TimeUnit.SECONDS)
                assertNotNull(message)
                message.socket.send("[\"OK\",\"${message.id}\",false,\"rate-limited: wait\"]")
                assertEquals(0, connections.poll(5, TimeUnit.SECONDS))
                assertEquals(WatchRelayFailure.RateLimited, transport.relayFailure())
            } finally {
                transport.close()
            }
        }
    }

    @Test
    fun proofOfWorkRejectionNeverMakesTheRelayLookConnected() {
        relay { server, client, events ->
            val identity = WatchIdentity()
            val transport = transport(server, client, identity)
            val connections = LinkedBlockingQueue<Int>()
            try {
                transport.start({ _, _ -> }, connections::add)
                val probe = events.poll(5, TimeUnit.SECONDS)
                assertNotNull(probe)
                probe.socket.send("[\"OK\",\"${probe.id}\",false,\"pow: 28 bits needed\"]")
                assertEquals(0, connections.poll(5, TimeUnit.SECONDS))
                assertEquals(WatchRelayFailure.Rejected, transport.relayFailure())
                assertEquals(1, server.requestCount)
            } finally {
                transport.close()
            }
        }
    }

    @Test
    fun aTemporaryIpRateBanIsRetriedLaterWithoutClaimingAConnection() {
        relay { server, client, events ->
            val identity = WatchIdentity()
            val transport = transport(server, client, identity)
            val connections = LinkedBlockingQueue<Int>()
            try {
                transport.start({ _, _ -> }, connections::add)
                val probe = events.poll(5, TimeUnit.SECONDS)
                assertNotNull(probe)
                probe.socket.send("[\"OK\",\"${probe.id}\",false,\"banned: too many rate-limit violations\"]")
                assertEquals(0, connections.poll(5, TimeUnit.SECONDS))
                assertEquals(WatchRelayFailure.RateLimited, transport.relayFailure())
            } finally {
                transport.close()
            }
        }
    }

    private data class Published(val socket: WebSocket, val id: String)

    private fun relay(block: (MockWebServer, OkHttpClient, LinkedBlockingQueue<Published>) -> Unit) {
        val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(
            clientTls.sslSocketFactory(),
            clientTls.trustManager,
        ).build()
        val events = LinkedBlockingQueue<Published>()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory())
            server.enqueue(
                MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val message = watchJson.parseToJsonElement(text) as JsonArray
                        when (message[0].jsonPrimitive.content) {
                            "REQ" -> webSocket.send("[\"EOSE\",${message[1]}]")
                            "EVENT" -> events.add(
                                Published(webSocket, message[1].jsonObject.getValue("id").jsonPrimitive.content),
                            )
                        }
                    }
                }).build(),
            )
            server.start()
            block(server, client, events)
        }
    }

    private fun transport(server: MockWebServer, client: OkHttpClient, identity: WatchIdentity): NostrWatchTransport {
        val invite = WatchInvite.create(identity.publicKey, System.currentTimeMillis())
            .copy(relays = listOf(server.url("/").toString().replace("https://", "wss://")))
        return NostrWatchTransport(invite, identity, client)
    }
}

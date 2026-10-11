package it.lorelore.nyanime.studio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class StudioNetworkTest {
    private class HeaderMap : LinkedHashMap<String, String>() {
        fun add(
            key: String,
            value: String,
        ) {
            put(key, value)
        }
    }
    private class Exchange {
        val responseHeaders = HeaderMap()
        val responseBody = ByteArrayOutputStream()
        var code = 200
        fun sendResponseHeaders(status: Int, length: Long) {
            code = status
        }
        fun close() { }
    }

    /** Independent local HTTP peer, without optional JDK modules absent from Android's boot classpath. */
    private class Peer : AutoCloseable {
        private val socket = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        private val handlers = ConcurrentHashMap<String, (Exchange) -> Unit>()
        private val executor = Executors.newCachedThreadPool { task ->
            Thread(task, "studio-test-peer").apply {
                isDaemon =
                    true
            }
        }
        val base = "http://127.0.0.1:${socket.localPort}"
        init {
            executor.execute {
                while (!socket.isClosed) {
                    val connection = try {
                        socket.accept()
                    } catch (_: SocketException) {
                        break
                    }
                    executor.execute {
                        connection.use { client ->
                            runCatching {
                                client.soTimeout = 3000
                                val input = client.getInputStream().bufferedReader()
                                val path = input.readLine()?.split(' ')?.getOrNull(1).orEmpty()
                                while (!input.readLine().isNullOrEmpty()) { }
                                val exchange = Exchange()
                                handlers[path]?.invoke(exchange) ?: exchange.sendResponseHeaders(404, -1)
                                val bytes = exchange.responseBody.toByteArray()
                                val head = buildString {
                                    append(
                                        "HTTP/1.1 ${exchange.code} Fixture\r\n" +
                                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n",
                                    )
                                    exchange.responseHeaders.forEach { (key, value) -> append("$key: $value\r\n") }
                                    append("\r\n")
                                }
                                client.getOutputStream().apply {
                                    write(head.toByteArray())
                                    write(bytes)
                                    flush()
                                }
                            }
                        }
                    }
                }
            }
        }
        fun createContext(path: String, handler: (Exchange) -> Unit) {
            handlers[path] = handler
        }
        override fun close() {
            socket.close()
            executor.shutdownNow()
        }
    }
    private fun server(block: (Peer, String) -> Unit) {
        Peer().use { peer -> block(peer, peer.base) }
    }
    private val variables = JsonObject(emptyMap())

    @Test fun `short bodies do not require filling the response byte limit`() = server { server, base ->
        server.createContext("/short") { exchange ->
            val bytes = "small result".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        runBlocking {
            val result = StudioNetwork(
                OkHttpClient(),
            ).request(StudioSource(baseUrl = base), StudioNode(kind = StudioKind.HTTP), "$base/short", variables)
            assertEquals("small result", result.body)
        }
    }

    @Test fun `an unapproved redirect is rejected before contacting its target`() = server { server, base ->
        server { destination, other ->
            val calls = AtomicInteger()
            destination.createContext("/") { exchange ->
                calls.incrementAndGet()
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }
            server.createContext("/redirect") { exchange ->
                exchange.responseHeaders.add("Location", "$other/private")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            runBlocking {
                try {
                    StudioNetwork(
                        OkHttpClient(),
                    ).request(
                        StudioSource(baseUrl = base),
                        StudioNode(kind = StudioKind.HTTP),
                        "$base/redirect",
                        variables,
                    )
                    fail("Unapproved origin was contacted")
                } catch (
                    _: IllegalArgumentException,
                ) { }
            }
            assertEquals(0, calls.get())
        }
    }

    @Test fun `invalid credential headers never expose their value in an exception`() = runBlocking {
        val sensitive = "private-value\ninvalid"
        val source = StudioSource(baseUrl = "https://fixture.test")
        val network = StudioNetwork(OkHttpClient()) { _, _ -> sensitive }
        try {
            network.request(
                source,
                StudioNode(
                    kind = StudioKind.HTTP,
                    parameters = mapOf("headers" to """{"Authorization":"{{secret.ACCESS}}"}"""),
                ),
                source.baseUrl,
                variables,
            )
            fail("Invalid header was accepted")
        } catch (failure: IllegalArgumentException) {
            assertFalse(failure.message.orEmpty().contains("private-value"))
        }
    }

    @Test fun `oversized response fails before returning a partial document`() = server { server, base ->
        server.createContext("/large") { exchange ->
            val bytes = ByteArray(2_097_153)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            runCatching { exchange.responseBody.use { it.write(bytes) } }
            exchange.close()
        }
        runBlocking {
            try {
                StudioNetwork(
                    OkHttpClient(),
                ).request(StudioSource(baseUrl = base), StudioNode(kind = StudioKind.HTTP), "$base/large", variables)
                fail("Oversized document was accepted")
            } catch (
                _: IllegalArgumentException,
            ) { }
        }
    }

    @Test fun `cancelling after headers closes a stalled response body`() {
        val headers = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val worker = thread(isDaemon = true, name = "studio-stalled-peer") {
                runCatching {
                    server.accept().use { client ->
                        client.soTimeout = 5000
                        val input = client.getInputStream().bufferedReader()
                        input.readLine()
                        while (!input.readLine().isNullOrEmpty()) { }
                        client.getOutputStream().apply {
                            write(
                                (
                                    "HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\n" +
                                        "Connection: close\r\n\r\nx"
                                    ).toByteArray(),
                            )
                            flush()
                        }
                        headers.countDown()
                        if (input.read() == -1) disconnected.countDown()
                    }
                }
            }
            runBlocking {
                val base = "http://127.0.0.1:${server.localPort}"
                val task = launch(Dispatchers.IO) {
                    StudioNetwork(
                        OkHttpClient(),
                    ).request(StudioSource(baseUrl = base), StudioNode(kind = StudioKind.HTTP), base, variables)
                }
                try {
                    assertTrue(headers.await(3, TimeUnit.SECONDS), "Response headers did not arrive")
                } finally {
                    task.cancelAndJoin()
                }
                assertTrue(disconnected.await(3, TimeUnit.SECONDS), "Cancellation left the response socket open")
            }
            worker.join(1000)
        }
    }
}

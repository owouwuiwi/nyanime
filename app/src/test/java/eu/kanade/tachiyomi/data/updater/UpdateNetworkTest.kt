package eu.kanade.tachiyomi.data.updater

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class UpdateNetworkTest {
    @Test
    fun `cancelling a body stalled after headers closes the socket without waiting for read timeout`() = runBlocking {
        ServerSocket(0).use { server ->
            val headersSent = CountDownLatch(1)
            val bodyStarted = CountDownLatch(1)
            val disconnected = CountDownLatch(1)
            thread(isDaemon = true, name = "update-fixture") {
                server.accept().use { socket ->
                    socket.soTimeout = 10000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { /* consume fixture request */ }
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\n".toByteArray())
                    socket.getOutputStream().flush()
                    headersSent.countDown()
                    try {
                        socket.getInputStream().read()
                    } catch (_: java.io.IOException) {
                        // The client closes a stalled connection on cancellation.
                    } finally {
                        disconnected.countDown()
                    }
                }
            }
            val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
            val transfer = async(Dispatchers.IO) {
                client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/update").build())
                    .awaitUpdateResponse().use {
                        bodyStarted.countDown()
                        it.body.source().readByte()
                    }
            }
            assertTrue(withContext(Dispatchers.IO) { headersSent.await(5, TimeUnit.SECONDS) })
            assertTrue(withContext(Dispatchers.IO) { bodyStarted.await(5, TimeUnit.SECONDS) })
            transfer.cancel()
            withTimeout(5000) { transfer.join() }
            assertTrue(withContext(Dispatchers.IO) { disconnected.await(5, TimeUnit.SECONDS) })
            assertTrue(transfer.isCancelled)
        }
    }

    @Test
    fun `closing a successful response releases its cancellation watcher`() = runBlocking {
        ServerSocket(0).use { server ->
            thread(isDaemon = true, name = "update-fixture-success") {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { /* consume fixture request */ }
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\ndone".toByteArray())
                    socket.getOutputStream().flush()
                }
            }
            val client = OkHttpClient()
            val content = withTimeout(5000) {
                withContext(Dispatchers.IO) {
                    client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/update").build())
                        .awaitUpdateResponse().use { it.body.string() }
                }
            }
            assertEquals("done", content)
        }
    }
}

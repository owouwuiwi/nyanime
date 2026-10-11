package it.lorelore.nyanime.backup.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RemoteResponseCancellationTest {
    @Test fun `network failures after headers remain retryable`() = runBlocking {
        val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long = throw IOException("Disconnected test transport")
            override fun close() = Unit
            override fun timeout() = Timeout.NONE
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength() = -1L
            override fun source() = source
        }
        val response = Response.Builder().request(Request.Builder().url("https://storage.example/").build())
            .protocol(Protocol.HTTP_1_1).message("OK").code(200).body(body).build()
        val failure = response.use { runCatching { it.readRemoteBytes(1024) }.exceptionOrNull() }
        assertTrue(failure is RemoteStorageException)
        assertEquals("network", (failure as RemoteStorageException).reason)
    }

    @Test fun `cancel closes a response that has already delivered headers`() = runBlocking<Unit> {
        val reading = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val body = object : ResponseBody() {
            private val blocked = object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    reading.countDown()
                    closed.await(5, TimeUnit.SECONDS)
                    throw IOException("Test stream closed")
                }
                override fun close() {
                    closed.countDown()
                }
                override fun timeout() = Timeout.NONE
            }.buffer()
            override fun contentType(): MediaType? = null
            override fun contentLength() = -1L
            override fun source() = blocked
        }
        val response = Response.Builder().request(Request.Builder().url("https://storage.example/").build())
            .protocol(Protocol.HTTP_1_1).message("OK").code(200).body(body).build()
        val task = async(Dispatchers.IO) { response.use { it.readRemoteBytes(1024) } }
        assertTrue(withContext(Dispatchers.IO) { reading.await(2, TimeUnit.SECONDS) })
        withTimeout(1000) { task.cancelAndJoin() }
        assertTrue(closed.count == 0L)
    }
}

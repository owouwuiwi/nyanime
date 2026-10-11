package it.lorelore.nyanime.reader.quality

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest

class QualityImageInputTest {
    private class Source(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        override fun close() {
            closed = true
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            check(!closed) { "Source already closed" }
            return super.read(bytes, offset, length)
        }
    }
    private val bytes = ByteArray(19001) { (it % 251).toByte() }
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    @Test fun `decoder can close its argument after a partial read without closing the owner`() = runBlocking {
        val source = Source(bytes)
        QualityImageInput(source).use { input ->
            input.decoderStream.use { assertEquals(123, it.read(ByteArray(123))) }
            assertFalse(source.closed)
            assertEquals(hash, input.fingerprint())
            assertEquals(hash, input.fingerprint())
        }
        assertTrue(source.closed)
    }

    @Test fun `a decoder consuming all bytes and closing still produces the complete fingerprint`() = runBlocking {
        val source = Source(bytes)
        QualityImageInput(source).use { input ->
            input.decoderStream.use { assertEquals(bytes.size, it.readBytes().size) }
            assertEquals(hash, input.fingerprint())
        }
        assertTrue(source.closed)
    }

    @Test fun `a decoder error releases the underlying input`() {
        val source = Source(bytes)
        assertThrows(IOException::class.java) {
            QualityImageInput(source).use { throw IOException("Fixture decode error") }
        }
        assertTrue(source.closed)
    }

    @Test fun `cancelled fingerprint releases the source without reading the remainder`() {
        val source = Source(bytes)
        assertThrows(CancellationException::class.java) {
            runBlocking {
                QualityImageInput(source).use { input ->
                    currentCoroutineContext()[Job]!!.cancel()
                    input.fingerprint()
                }
            }
        }
        assertTrue(source.closed)
        assertEquals(bytes.size, source.available())
    }
}

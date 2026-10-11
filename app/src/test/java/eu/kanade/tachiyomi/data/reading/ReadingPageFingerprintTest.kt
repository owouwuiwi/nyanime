package eu.kanade.tachiyomi.data.reading

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReadingPageFingerprintTest {
    @Test fun equivalenceRequiresOriginalBytesNotPageNumbersOrCounts() = runTest {
        val original = ReadingPageFingerprint.calculate { byteArrayOf(1, 2, 3).inputStream() }
        val same = ReadingPageFingerprint.calculate { byteArrayOf(1, 2, 3).inputStream() }
        val different = ReadingPageFingerprint.calculate { byteArrayOf(1, 2, 4).inputStream() }
        assertTrue(ReadingPageFingerprint.matches(original, same))
        assertFalse(ReadingPageFingerprint.matches(original, different))
        assertFalse(ReadingPageFingerprint.matches(null, null))
        assertFalse(ReadingPageFingerprint.matches("", ""))
        assertNull(ReadingPageFingerprint.calculate { byteArrayOf().inputStream() })
    }

    @Test fun inputsAreBoundedAndStreamsClosed() = runTest {
        var closed = false
        var remaining = ReadingPageFingerprint.MAX_BYTES + 1
        val result = ReadingPageFingerprint.calculate {
            object : java.io.InputStream() {
                override fun read(): Int = if (remaining-- > 0) 0 else -1
                override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
                    if (remaining <= 0) return -1
                    val read = minOf(count, remaining)
                    remaining -= read
                    return read
                }
                override fun close() {
                    closed = true
                }
            }
        }
        assertNull(result)
        assertTrue(closed)
    }
}

package eu.kanade.tachiyomi.data.download

import com.hippo.unifile.UniFile
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class DownloadIndexIOTest {
    @Test
    fun `an empty folder is valid but an inaccessible folder defers reconciliation`() {
        val folder = mockk<UniFile>()
        every { folder.listFiles() } returns emptyArray()
        assertEquals(emptyList<UniFile>(), folder.listDownloadIndexFiles())
        every { folder.listFiles() } returns null
        assertThrows(IOException::class.java) { folder.listDownloadIndexFiles() }
    }

    @Test
    fun `an index at the limit remains compatible while oversized input is rejected`() {
        val bytes = ByteArray(128) { it.toByte() }
        assertArrayEquals(bytes, readDownloadIndex(ByteArrayInputStream(bytes), limit = 128))
        assertThrows(IOException::class.java) {
            readDownloadIndex(ByteArrayInputStream(bytes + byteArrayOf(1)), limit = 128)
        }
    }

    @Test
    fun `an empty index is passed to the decoder for normal corrupt-cache recovery`() {
        assertArrayEquals(byteArrayOf(), readDownloadIndex(ByteArrayInputStream(byteArrayOf())))
    }
}

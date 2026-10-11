package it.lorelore.nyanime.studio

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class SourceBytesTest {
    @Test fun `exact budget including empty reads is accepted`() {
        assertArrayEquals(byteArrayOf(), SourceBytes.read(ByteArrayInputStream(byteArrayOf()), 0))
        val bytes = ByteArray(32 * 1024) { (it % 251).toByte() }
        assertArrayEquals(bytes, SourceBytes.read(ByteArrayInputStream(bytes), bytes.size))
    }

    @Test fun `one byte beyond the budget is rejected before a result is returned`() {
        assertThrows(IllegalArgumentException::class.java) {
            SourceBytes.read(ByteArrayInputStream(ByteArray(32 * 1024 + 1)), 32 * 1024)
        }
        assertThrows(IllegalArgumentException::class.java) { SourceBytes.read(ByteArrayInputStream(byteArrayOf(1)), 0) }
    }
}

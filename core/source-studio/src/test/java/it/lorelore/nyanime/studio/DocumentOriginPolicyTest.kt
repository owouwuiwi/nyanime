package it.lorelore.nyanime.studio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.net.InetAddress

class DocumentOriginPolicyTest {
    private val policy = DocumentOriginPolicy(setOf("api.example.invalid", "*.files.example.invalid"))

    @Test fun `only declared HTTPS hosts without credentials ports or fragments are accepted`() {
        assertEquals("api.example.invalid", policy.requireUrl("https://api.example.invalid/lookup?q=fiction").host)
        assertEquals("shard.files.example.invalid", policy.requireUrl("https://shard.files.example.invalid/file").host)
        listOf(
            "http://api.example.invalid",
            "https://user@api.example.invalid",
            "https://api.example.invalid:444/file",
            "https://api.example.invalid/file#key",
            "https://api.example.invalid.evil.invalid",
            "https://files.example.invalid",
            "file:///private/document",
            "https://127.0.0.1",
        ).forEach { address ->
            assertThrows(IllegalArgumentException::class.java) { policy.requireUrl(address) }
        }
    }

    @Test fun `private reserved mapped and mixed DNS answers are rejected`() {
        val public = InetAddress.getByAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34))
        assertEquals(listOf(public), policy.requirePublic(listOf(public)))
        val values = listOf(
            byteArrayOf(0, 0, 0, 0), byteArrayOf(127, 0, 0, 1), byteArrayOf(10, 1, 2, 3),
            byteArrayOf(172.toByte(), 16, 0, 1), byteArrayOf(192.toByte(), 168.toByte(), 0, 1),
            byteArrayOf(
                169.toByte(),
                254.toByte(),
                1,
                1,
            ),
            byteArrayOf(100, 64, 1, 1), byteArrayOf(198.toByte(), 18, 0, 1),
            byteArrayOf(
                192.toByte(),
                0,
                2,
                1,
            ),
            byteArrayOf(198.toByte(), 51, 100, 1), byteArrayOf(203.toByte(), 0, 113, 1),
            byteArrayOf(224.toByte(), 1, 1, 1), ByteArray(16), ByteArray(16).apply { this[15] = 1 },
            ByteArray(16).apply {
                this[0] = 0xfc.toByte()
                this[15] = 1
            },
        )
        values.forEach { bytes ->
            val private = InetAddress.getByAddress(bytes)
            assertThrows(IllegalArgumentException::class.java) { policy.requirePublic(listOf(private)) }
            assertThrows(IllegalArgumentException::class.java) { policy.requirePublic(listOf(public, private)) }
        }
    }

    @Test fun `invalid component host declarations are rejected before HTTP setup`() {
        listOf(
            emptySet(),
            setOf("*"),
            setOf("*.invalid"),
            setOf("127.0.0.1"),
            setOf("example.invalid:443"),
            setOf("https://example.invalid"),
            setOf("-bad.example.invalid"),
        ).forEach { hosts ->
            assertThrows(IllegalArgumentException::class.java) { DocumentOriginPolicy(hosts) }
        }
    }
}

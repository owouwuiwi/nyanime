package eu.kanade.tachiyomi.data.cast

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CompanionCryptoTest {
    @Test
    fun `keys and encryption agree with the TypeScript receiver vector above 32 bit sequences`() {
        val keys = CompanionCrypto.derive(ByteArray(32) { it.toByte() }, "nyanime-cast/1\nvector")
        fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
        assertEquals("091658ee5f58a8d04ad5d746c4185026d97db3fd07e9dda0fb2a10465a46d449", keys.client.hex())
        assertEquals("a2317ea395bfe6d94f212022ad7328cfa750bcfb87c96bdcb82f3ebb108b0905", keys.server.hex())
        assertEquals("109905", keys.code)
        val body = buildJsonObject { put("type", "status") }
        val session = "a".repeat(32)
        val envelope = CompanionCrypto.seal(keys.client, session, "client", 4294967297, body)
        assertEquals("PC2sJNkzJtY9SDoscnrpuSQxq1ui8OWD1f96ZVDuYSvd", envelope.getValue("data").jsonPrimitive.content)
        assertEquals(body, CompanionCrypto.open(keys.client, session, "client", 4294967297, envelope))
        assertThrows(Exception::class.java) {
            CompanionCrypto.open(keys.server, session, "client", 4294967297, envelope)
        }
        assertThrows(Exception::class.java) {
            CompanionCrypto.open(keys.client, session, "server", 4294967297, envelope)
        }
        assertThrows(Exception::class.java) {
            CompanionCrypto.open(keys.client, session, "client", 4294967298, envelope)
        }
    }

    @Test
    fun `changed key reveal is rejected before pairing`() {
        val client = CompanionCrypto()
        val server = CompanionCrypto()
        assertThrows(IllegalArgumentException::class.java) {
            client.complete(
                "a".repeat(32),
                "b".repeat(32),
                "Telefono",
                server.publicKey,
                server.nonce,
                client.commitment,
            )
        }
        assertThrows(IllegalArgumentException::class.java) { CompanionCrypto.decode("AA", 1) }
    }

    @Test
    fun `manual address accepts only LAN IPv4 and explicit valid ports`() {
        assertEquals(38473, CompanionClient.address("192.168.1.39").port)
        assertEquals(40000, CompanionClient.address("http://10.0.0.2:40000").port)
        listOf("127.0.0.1", "8.8.8.8", "example.com", "192.168.1.1:0", "192.168.1.1/path", "192.168.1.1@10.0.0.1")
            .forEach { address ->
                assertThrows(IllegalArgumentException::class.java) { CompanionClient.address(address) }
            }
    }
}

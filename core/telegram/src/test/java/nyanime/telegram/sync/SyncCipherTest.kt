package nyanime.telegram.sync

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class SyncCipherTest {
    private val space = UUID.randomUUID().toString()
    private val id = UUID.randomUUID().toString()
    private val key = SyncCipher.newKey()
    private val text = """{"private":"progress","position":34}""".toByteArray()

    @Test fun roundtripUsesFreshNonceAndCiphertext() {
        val a = SyncCipher.encrypt(10, space, 1, id, key, text)
        val b = SyncCipher.encrypt(10, space, 1, id, key, text)
        assertNotEquals(a.nonce, b.nonce)
        assertNotEquals(a.ciphertext, b.ciphertext)
        assertArrayEquals(text, SyncCipher.decrypt(10, space, key, a))
    }

    @Test fun accountArchiveEpochAndMessageIdentityCannotBeSubstituted() {
        val a = SyncCipher.encrypt(10, space, 1, id, key, text)
        listOf(
            { SyncCipher.decrypt(11, space, key, a) },
            { SyncCipher.decrypt(10, UUID.randomUUID().toString(), key, a) },
            { SyncCipher.decrypt(10, space, key, a.copy(epoch = 2)) },
            { SyncCipher.decrypt(10, space, key, a.copy(id = UUID.randomUUID().toString())) },
            { SyncCipher.decrypt(10, space, SyncCipher.newKey(), a) },
        ).forEach { action -> assertThrows(Exception::class.java) { action() } }
    }

    @Test fun corruptCiphertextAndFutureProtocolFailClosed() {
        val a = SyncCipher.encrypt(10, space, 1, id, key, text)
        val bytes = SyncCipher.decode(a.ciphertext).apply { this[0] = (this[0].toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) {
            SyncCipher.decrypt(10, space, key, a.copy(ciphertext = SyncCipher.encode(bytes)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SyncCipher.decrypt(10, space, key, a.copy(version = 2))
        }
    }

    @Test fun recoveryCodeAcceptsFormattingAndDetectsTypos() {
        val code = SyncRecoveryCode.format(key)
        assertArrayEquals(key, SyncRecoveryCode.parse(code.lowercase().replace("-", " ")))
        val wrong = code.dropLast(1) + if (code.last() == 'A') "B" else "A"
        assertThrows(IllegalArgumentException::class.java) { SyncRecoveryCode.parse(wrong) }
    }
}

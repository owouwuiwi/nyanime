package nyanime.telegram.sync

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.UUID

class SyncBackupCipherTest {
    @Test fun streamsSeveralSegmentsAndRejectsCorruptionTruncationAndAnotherAccount() {
        val key = SyncCipher.newKey()
        val clear = ByteArray(3 * 1024 * 1024 + 7) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        SyncBackupCipher.encrypt(
            clear.inputStream(),
            output,
            clear.size.toLong(),
            10,
            UUID.randomUUID().toString(),
            1,
            UUID.randomUUID().toString(),
            key,
        )
        val encrypted = output.toByteArray()
        fun decrypt(bytes: ByteArray, account: Long = 10): ByteArray {
            val input = bytes.inputStream()
            val header = SyncBackupCipher.readHeader(input)
            val result = ByteArrayOutputStream()
            SyncBackupCipher.decrypt(input, result, account, header, key)
            return result.toByteArray()
        }
        assertArrayEquals(clear, decrypt(encrypted))
        assertThrows(Exception::class.java) { decrypt(encrypted.copyOf(encrypted.size - 1)) }
        assertThrows(Exception::class.java) { decrypt(encrypted, 11) }
        val damaged = encrypted.clone()
        damaged[2 * 1024 * 1024] = (damaged[2 * 1024 * 1024].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { decrypt(damaged) }
    }
}

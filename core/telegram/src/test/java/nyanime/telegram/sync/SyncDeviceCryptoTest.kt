package nyanime.telegram.sync

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class SyncDeviceCryptoTest {
    private fun device() = SyncDeviceCrypto.generate(UUID.randomUUID().toString())

    @Test fun deviceKeysRoundtripAndSignaturesAreNotInterchangeable() {
        val first = device()
        val second = device()
        val content = "private-change".toByteArray()
        val restored = SyncDeviceCrypto.importAfterLocalUnwrapping(SyncDeviceCrypto.exportForLocalWrapping(first))
        val signature = SyncDeviceCrypto.sign(restored, content)
        assertTrue(SyncDeviceCrypto.verify(first.public, content, signature))
        assertFalse(SyncDeviceCrypto.verify(second.public, content, signature))
        assertFalse(SyncDeviceCrypto.verify(first.public, "other-change".toByteArray(), signature))
    }

    @Test fun keyRotationGrantCanOnlyBeOpenedByItsRecipientInItsExactContext() {
        val first = device()
        val removed = device()
        val key = SyncCipher.newKey()
        val context = "account:1/space:2/epoch:3".toByteArray()
        val encrypted = SyncDeviceCrypto.seal(first.public, key, context)
        assertArrayEquals(key, SyncDeviceCrypto.open(first, encrypted, context))
        assertThrows(Exception::class.java) { SyncDeviceCrypto.open(removed, encrypted, context) }
        assertThrows(Exception::class.java) { SyncDeviceCrypto.open(first, encrypted, byteArrayOf(1)) }
    }

    @Test fun pairingAuthenticatesPhysicalQrApprovalAccountDeviceAndDeadline() {
        val phone = device()
        val request = SyncPairing.request(10, phone.public, 1000)
        val key = SyncCipher.newKey()
        val grant = SyncPairing.approve(request, 10, 1001, key)
        assertArrayEquals(key, SyncPairing.accept(request, grant, phone, 10, 1002))
        assertThrows(Exception::class.java) { SyncPairing.accept(request, grant, phone, 11, 1002) }
        assertThrows(Exception::class.java) { SyncPairing.accept(request, grant, phone, 10, request.expiresAt) }
        assertThrows(Exception::class.java) { SyncPairing.accept(request, grant, device(), 10, 1002) }
        val attacker = SyncPairing.approve(request.copy(secret = SyncCipher.encode(SyncCipher.newKey())), 10, 1001, key)
        assertThrows(Exception::class.java) { SyncPairing.accept(request, attacker, phone, 10, 1002) }
    }
}

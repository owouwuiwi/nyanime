package nyanime.telegram.sync

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class SyncArchiveRootTest {
    @Test fun recoverySurvivesRotationWithoutSharingItsPrivateIdentityWithDevices() {
        val account = 10L
        val a = SyncDeviceCrypto.generate(UUID.randomUUID().toString())
        val b = SyncDeviceCrypto.generate(UUID.randomUUID().toString())
        val code = SyncRecoveryCode.create()
        val signed = SyncArchiveBootstrap.create(account, UUID.randomUUID().toString(), a, code)
        val root = SyncArchiveBootstrap.verify(signed, account)
        val recovered = SyncArchiveBootstrap.recover(signed, account, code)
        assertEquals(root.recovery, recovered.public)
        assertArrayEquals(
            SyncArchiveBootstrap.openKey(root, account, a),
            SyncArchiveBootstrap.openKey(root, account, recovered),
        )
        val original = SyncArchiveBootstrap.initial(root, 1)
        val key = SyncCipher.newKey()
        val control = SyncMembership.propose(original, recovered, listOf(b.public), account, key)
        val (current, change) = SyncMembership.accept(original, control, account, 2)
        assertArrayEquals(key, SyncMembership.openKey(change, account, b))
        assertArrayEquals(key, SyncMembership.openKey(change, account, recovered, current.recovery))
        assertThrows(Exception::class.java) { SyncMembership.openKey(change, account, a) }
        assertThrows(Exception::class.java) {
            SyncArchiveBootstrap.recover(signed, account, SyncRecoveryCode.create())
        }
        assertThrows(Exception::class.java) { SyncArchiveBootstrap.verify(signed, account + 1) }
    }

    @Test fun aRemovedDeviceCannotPretendToBeTheRecoveryAuthority() {
        val a = SyncDeviceCrypto.generate(UUID.randomUUID().toString())
        val b = SyncDeviceCrypto.generate(UUID.randomUUID().toString())
        val root = SyncArchiveBootstrap.verify(
            SyncArchiveBootstrap.create(10, UUID.randomUUID().toString(), a, SyncRecoveryCode.create()),
            10,
        )
        val initial = SyncArchiveBootstrap.initial(root, 1)
        val signed = SyncMembership.propose(initial, a, listOf(b.public), 10, SyncCipher.newKey())
        val current = SyncMembership.accept(initial, signed, 10, 2).first
        val forgedState = current.copy(devices = current.devices + (a.public.id to a.public))
        val forged = SyncMembership.propose(forgedState, a, listOf(a.public), 10, SyncCipher.newKey())
        assertThrows(Exception::class.java) { SyncMembership.accept(current, forged, 10, 3) }
    }
}

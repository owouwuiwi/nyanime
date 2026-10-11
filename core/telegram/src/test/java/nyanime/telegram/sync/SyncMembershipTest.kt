package nyanime.telegram.sync

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class SyncMembershipTest {
    @Test fun revocationRotatesFutureKeysAndRejectsControlsFromTheRemovedDevice() {
        val a = SyncDeviceCrypto.generate(UUID.randomUUID().toString())
        val b = SyncDeviceCrypto.generate(UUID.randomUUID().toString())
        val initial = SyncMembershipState(
            UUID.randomUUID().toString(),
            1,
            UUID.randomUUID().toString(),
            1,
            mapOf(a.public.id to a.public, b.public.id to b.public),
        )
        val key = SyncCipher.newKey()
        val signed = SyncMembership.propose(initial, a, listOf(a.public), 10, key)
        val (next, change) = SyncMembership.accept(initial, signed, 10, 2)
        assertArrayEquals(key, SyncMembership.openKey(change, 10, a))
        assertThrows(Exception::class.java) { SyncMembership.openKey(change, 10, b) }
        val stale = SyncMembership.propose(initial, b, listOf(b.public), 10, SyncCipher.newKey())
        assertThrows(Exception::class.java) { SyncMembership.accept(next, stale, 10, 3) }
        assertThrows(Exception::class.java) { SyncMembership.accept(initial, signed, 11, 2) }
        assertThrows(Exception::class.java) { SyncMembership.accept(next, signed, 10, 3) }
    }
}

package it.lorelore.nyanime.cloud.sync

import nyanime.telegram.sync.SyncDeviceCrypto
import nyanime.telegram.sync.SyncMembershipState
import nyanime.telegram.sync.SyncReaderCapabilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class SyncReaderCapabilityTest {
    @Test fun portableProofRequiresOriginalAuthorAndExactAccountSpaceEpochAndMembershipControl() {
        val device = SyncDeviceCrypto.generate(UUID.randomUUID().toString())
        val membership = SyncMembershipState(
            UUID.randomUUID().toString(),
            1,
            UUID.randomUUID().toString(),
            10,
            mapOf(device.public.id to device.public),
        )
        val signed = SyncReaderCapabilities.sign(12, membership, device)
        assertEquals(device.public.id, SyncReaderCapabilities.verify(signed, 12, membership).author)
        assertThrows(Exception::class.java) { SyncReaderCapabilities.verify(signed, 13, membership) }
        for (changed in listOf(
            membership.copy(space = UUID.randomUUID().toString()),
            membership.copy(epoch = 2),
            membership.copy(control = UUID.randomUUID().toString()),
            membership.copy(devices = emptyMap()),
        )) {
            assertThrows(Exception::class.java) { SyncReaderCapabilities.verify(signed, 12, changed) }
        }
        assertThrows(Exception::class.java) {
            SyncReaderCapabilities.verify(
                signed.copy(payload = signed.payload.replace("\"packingVersion\":1", "\"packingVersion\":2")),
                12,
                membership,
            )
        }
        assertThrows(Exception::class.java) {
            SyncReaderCapabilities.verify(signed.copy(payload = " ".repeat(8193)), 12, membership)
        }
    }
}

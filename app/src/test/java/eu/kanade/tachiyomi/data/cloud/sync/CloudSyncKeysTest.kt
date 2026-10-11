package eu.kanade.tachiyomi.data.cloud.sync

import nyanime.telegram.sync.SyncArchiveBootstrap
import nyanime.telegram.sync.SyncCipher
import nyanime.telegram.sync.SyncDelivery
import nyanime.telegram.sync.SyncDeviceCrypto
import nyanime.telegram.sync.SyncMembership
import nyanime.telegram.sync.SyncPacket
import nyanime.telegram.sync.SyncReceipt
import nyanime.telegram.sync.SyncRecoveryCode
import nyanime.telegram.sync.syncJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class CloudSyncKeysTest {
    private val account = 1234L
    private val device = SyncDeviceCrypto.generate(UUID.randomUUID().toString())
    private val other = SyncDeviceCrypto.generate(UUID.randomUUID().toString())
    private fun keys(): CloudSyncKeys {
        val root = SyncArchiveBootstrap.create(account, UUID.randomUUID().toString(), device, SyncRecoveryCode.create())
        val first = CloudSyncKeys.first(account, -1, root, 10, device)
        val control = SyncMembership.propose(
            first.membership,
            device,
            listOf(device.public, other.public),
            account,
            SyncCipher.newKey(),
        )
        return first.accept(control, 20, device)
    }

    @Test fun signedHistorySurvivesRemovalOfTelegramPackets() {
        val keys = keys()
        val preserved = keys.recoveryControls { error("No server packet should be needed") }
        val moved = keys.copy(chat = -2)
        assertEquals(preserved, moved.recoveryControls { error("Missing packet") })
        assertEquals(keys.membership.devices, moved.membership.devices)
        assertEquals(keys.epochs, moved.epochs)
    }

    @Test fun oldKeyringsCanRecoverFromLocallyRetainedControls() {
        val keys = keys()
        val old = keys.copy(controls = emptyMap())
        assertEquals(keys.controls, old.recoveryControls { member -> keys.controls.getValue(member.epoch) })
    }

    @Test fun corruptedAuthorizationIsRejectedBeforeCreatingAChannel() {
        val keys = keys()
        val bad = keys.copy(controls = keys.controls.mapValues { (_, signed) -> signed.copy(signature = "invalid") })
        assertThrows(Exception::class.java) { bad.recoveryControls { error("Not needed") } }
        assertThrows(Exception::class.java) {
            keys.copy(history = mapOf(keys.epoch to keys.membership)).recoveryControls { error("Missing") }
        }
    }

    private fun delivery(keys: CloudSyncKeys, message: Long = 40): SyncDelivery = SyncDelivery(
        SyncPacket(
            kind = "control",
            id = keys.membership.control,
            space = keys.space,
            payload = syncJson.encodeToString(keys.controls.getValue(keys.epoch)),
        ),
        SyncReceipt(message, 1000),
    )

    @Test fun missingLegacyPacketsCanRecoverFromVerifiedReplacementHistory() {
        val keys = keys()
        val replacement = delivery(keys)
        val restored = CloudSyncRecoveryControls.restore(
            keys.copy(controls = emptyMap()),
            { null },
            mapOf(replacement.packet.id to replacement),
        )
        assertEquals(keys.controls, restored)
        assertEquals(20L, keys.membership.messageId)
    }

    @Test fun replacementPacketCannotSubstituteDifferentAuthorization() {
        val keys = keys()
        val changed = SyncMembership.propose(
            keys.history.getValue(1),
            device,
            listOf(device.public),
            account,
            SyncCipher.newKey(),
        )
        val replacement = delivery(keys).let {
            it.copy(packet = it.packet.copy(payload = syncJson.encodeToString(changed)))
        }
        assertThrows(Exception::class.java) {
            CloudSyncRecoveryControls.restore(
                keys.copy(controls = emptyMap()),
                { null },
                mapOf(replacement.packet.id to replacement),
            )
        }
    }

    @Test fun tamperedLocalPacketIsNeverSilentlyReplaced() {
        val keys = keys()
        val correct = delivery(keys)
        val changed = correct.copy(packet = correct.packet.copy(space = UUID.randomUUID().toString()))
        assertThrows(IllegalArgumentException::class.java) {
            CloudSyncRecoveryControls.restore(
                keys.copy(controls = emptyMap()),
                { changed },
                mapOf(correct.packet.id to correct),
            )
        }
    }

    @Test fun absentAuthorizationHistoryRemainsAnError() {
        val keys = keys()
        assertThrows(IllegalStateException::class.java) {
            CloudSyncRecoveryControls.restore(keys.copy(controls = emptyMap()), { null }, emptyMap())
        }
    }
}

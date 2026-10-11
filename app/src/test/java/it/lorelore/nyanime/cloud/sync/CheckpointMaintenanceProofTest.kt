package it.lorelore.nyanime.cloud.sync

import eu.kanade.tachiyomi.data.cloud.sync.CloudSyncCheckpoints
import eu.kanade.tachiyomi.data.cloud.sync.CloudSyncKeys
import io.mockk.mockk
import kotlinx.serialization.json.JsonPrimitive
import nyanime.telegram.sync.SyncArchiveBootstrap
import nyanime.telegram.sync.SyncChange
import nyanime.telegram.sync.SyncCheckpointChunk
import nyanime.telegram.sync.SyncCheckpointCommit
import nyanime.telegram.sync.SyncCheckpointPart
import nyanime.telegram.sync.SyncCheckpoints
import nyanime.telegram.sync.SyncCipher
import nyanime.telegram.sync.SyncDelivery
import nyanime.telegram.sync.SyncDeviceCrypto
import nyanime.telegram.sync.SyncDot
import nyanime.telegram.sync.SyncPacket
import nyanime.telegram.sync.SyncReceipt
import nyanime.telegram.sync.SyncRecoveryCode
import nyanime.telegram.sync.SyncRef
import nyanime.telegram.sync.SyncVector
import nyanime.telegram.sync.syncJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class CheckpointMaintenanceProofTest {
    @Test fun cryptographicProofBindsAccountMembershipEpochPartAndExactContent() {
        val device = SyncDeviceCrypto.generate(UUID.randomUUID().toString())
        val keys = CloudSyncKeys.first(
            12,
            -100,
            SyncArchiveBootstrap.create(
                12,
                UUID.randomUUID().toString(),
                device,
                SyncRecoveryCode.create(),
            ),
            1,
            device,
        )
        val checker = CloudSyncCheckpoints(mockk(), mockk(), mockk(), device)
        val id = UUID.randomUUID().toString()
        val change = SyncChange(
            SyncDot(device.public.id, 1),
            SyncVector(),
            SyncRef("item", 31, "/title"),
            "position",
            JsonPrimitive(20),
            100,
        )
        val chunk = SyncCheckpointChunk(checkpoint = id, changes = listOf(change))
        val envelope = SyncCipher.encrypt(
            12,
            keys.space,
            1,
            UUID.randomUUID().toString(),
            keys.key(),
            syncJson.encodeToString(chunk).toByteArray(),
        )
        val part = SyncCheckpointPart(envelope.id, SyncCheckpoints.hash(syncJson.encodeToString(envelope)), 90)
        val commit = SyncCheckpointCommit(
            id = id,
            space = keys.space,
            epoch = 1,
            author = device.public.id,
            throughMessage = 80,
            parts = listOf(part),
            maintenanceVersion = 1,
        )
        val signed = SyncCheckpoints.sign(commit, 12, device)
        val wrapped = SyncCipher.encrypt(
            12,
            keys.space,
            1,
            id,
            keys.key(),
            syncJson.encodeToString(signed).toByteArray(),
        )
        val delivery = SyncDelivery(
            SyncPacket(
                kind = "commit",
                id = id,
                space = keys.space,
                payload = syncJson.encodeToString(wrapped),
            ),
            SyncReceipt(100, 1),
        )
        val partDelivery = SyncDelivery(
            SyncPacket(
                kind = "checkpoint",
                id = envelope.id,
                space = keys.space,
                payload = syncJson.encodeToString(envelope),
            ),
            SyncReceipt(90, 1),
        )
        assertEquals(commit, checker.verifiedCommit(delivery, keys))
        assertEquals(chunk, checker.verifiedChunk(partDelivery, part, commit, keys))
        assertThrows(Exception::class.java) { checker.verifiedCommit(delivery, keys.copy(account = 99)) }
        assertThrows(Exception::class.java) {
            checker.verifiedChunk(partDelivery, part.copy(hash = "0".repeat(64)), commit, keys)
        }
        assertThrows(Exception::class.java) {
            checker.verifiedChunk(partDelivery.copy(receipt = SyncReceipt(91, 1)), part, commit, keys)
        }
        assertThrows(Exception::class.java) {
            checker.verifiedChunk(partDelivery, part, commit.copy(epoch = 2), keys)
        }
        // An earlier application version ignores the optional capability inside the signed JSON;
        // it still sees the same version-1 payload, field values and part references.
        val legacyJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val decoded = legacyJson.parseToJsonElement(signed.payload).toString()
        assertTrue(decoded.contains("\"version\":1"))
        assertTrue(decoded.contains(part.id))
        assertThrows(Exception::class.java) {
            SyncCheckpoints.verify(
                signed.copy(
                    payload = signed.payload.replace(
                        "\"maintenanceVersion\":1",
                        "\"maintenanceVersion\":2",
                    ),
                ),
                12,
                keys.membership,
            )
        }
    }
}

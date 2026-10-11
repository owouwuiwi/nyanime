package it.lorelore.nyanime.cloud.sync

import eu.kanade.tachiyomi.data.cloud.sync.CloudSyncCheckpoints
import eu.kanade.tachiyomi.data.cloud.sync.CloudSyncKeys
import eu.kanade.tachiyomi.data.cloud.sync.CloudSyncPacketFiles
import eu.kanade.tachiyomi.data.cloud.sync.CloudSyncStore
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
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
import nyanime.telegram.sync.SyncEnvelope
import nyanime.telegram.sync.SyncPacket
import nyanime.telegram.sync.SyncReaderCapabilities
import nyanime.telegram.sync.SyncReceipt
import nyanime.telegram.sync.SyncRecoveryCode
import nyanime.telegram.sync.SyncRef
import nyanime.telegram.sync.SyncVector
import nyanime.telegram.sync.syncJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID

class PackedCheckpointProofTest {
    @TempDir lateinit var directory: File
    private fun id() = UUID.randomUUID().toString()
    private val device = SyncDeviceCrypto.generate(id())
    private val keys = CloudSyncKeys.first(
        12,
        -100,
        SyncArchiveBootstrap.create(12, id(), device, SyncRecoveryCode.create()),
        1,
        device,
    )
    private val store = mockk<CloudSyncStore>()
    private val files = mockk<CloudSyncPacketFiles>()
    private val checker = CloudSyncCheckpoints(store, files, mockk(), device)

    @Test fun resumingAnOldPreparedCommitKeepsItsBytesAndStillRequiresANewPackingAnnouncement() = runBlocking {
        val legacy = SyncCheckpointCommit(
            id = id(),
            space = keys.space,
            epoch = 1,
            author = device.public.id,
            throughMessage = 80,
            parts = emptyList(),
            maintenanceVersion = 1,
        )
        val envelope = SyncCipher.encrypt(
            12,
            keys.space,
            1,
            legacy.id,
            keys.key(),
            syncJson.encodeToString(SyncCheckpoints.sign(legacy, 12, device)).toByteArray(),
        )
        val delivery = SyncDelivery(
            SyncPacket(
                kind = "commit",
                id = legacy.id,
                space = keys.space,
                payload = syncJson.encodeToString(envelope),
            ),
            SyncReceipt(90, 100),
        )
        val prepared = CloudSyncCheckpoints.Prepared(legacy.id, 1, 7, 80, emptyList(), commitPrepared = true)
        val state = mutableMapOf("checkpoint-pending" to syncJson.encodeToString(prepared))
        every { store.state(any()) } answers { state[firstArg()] }
        every { store.state(any(), any()) } answers { state[firstArg()] = secondArg() }
        every { files.prepared(legacy.id) } returns delivery.packet
        every { store.messageFor(legacy.id) } returns 90L
        every { files.delivery(90) } returns delivery
        justRun { store.checkpointConfirmed(7, legacy.id, 90, 100) }
        justRun { files.removePrepared(legacy.id) }
        assertEquals(delivery, checker.publish(keys)) // No transport call or re-encryption is permitted.
        assertEquals(keys.membership.control, state["maintenance-announced"])
        assertFalse(state.containsKey("packing-announced"))
        assertFalse(checker.packingReady(keys))
        assertEquals("", state["checkpoint-pending"])
    }

    @Test fun containerIndexAndEachChunkRemainBoundToAccountEpochCheckpointAndCommit() {
        val checkpoint = id()
        val chunks = List(3) { position ->
            SyncCheckpointChunk(
                checkpoint = checkpoint,
                changes = listOf(
                    SyncChange(
                        SyncDot(device.public.id, position + 1L),
                        SyncVector(mapOf(device.public.id to position.toLong())),
                        SyncRef("item", 31, "/title", "/part/$position"),
                        "position",
                        JsonPrimitive(30),
                        100,
                    ),
                ),
            )
        }
        fun encrypted(value: String, account: Long = 12) = SyncCipher.encrypt(
            account,
            keys.space,
            1,
            id(),
            keys.key(),
            value.toByteArray(),
        ).let { SyncPacket(kind = "checkpoint", id = it.id, space = keys.space, payload = syncJson.encodeToString(it)) }
        fun verify(children: List<SyncPacket>, mismatch: String? = null) {
            var offset = 0L
            val index = SyncCheckpointPackIndex(
                checkpoint = checkpoint,
                chunks = children.map {
                    SyncCheckpointPack.entry(it, offset).also { entry -> offset += 4L + entry.bytes }
                },
            )
            val parent = encrypted(syncJson.encodeToString(index)).copy(kind = "checkpoint-pack")
            val file = File(directory, id())
            file.outputStream().use { SyncCheckpointPack.write(it, parent, children.asSequence()) }
            every { files.packFile(parent.id, false) } returns file
            val part = SyncCheckpointPart(parent.id, SyncCheckpoints.hash(parent.payload), 90, chunks.size)
            val commit = SyncCheckpointCommit(
                id = checkpoint,
                space = keys.space,
                epoch = 1,
                author = device.public.id,
                throughMessage = 80,
                parts = listOf(part),
                maintenanceVersion = 1,
                packingVersion = 1,
                formatVersion = 2,
                readerCapability = SyncReaderCapabilities.sign(12, keys.membership, device),
            )
            val delivery = SyncDelivery(parent, SyncReceipt(90, 100))
            val signed = SyncCheckpoints.sign(commit, 12, device)
            val sealedCommit = SyncCipher.encrypt(
                12,
                keys.space,
                1,
                commit.id,
                keys.key(),
                syncJson.encodeToString(signed).toByteArray(),
            )
            assertEquals(
                commit,
                checker.verifiedCommit(
                    SyncDelivery(
                        SyncPacket(
                            kind = "commit",
                            id = commit.id,
                            space = keys.space,
                            payload = syncJson.encodeToString(sealedCommit),
                        ),
                        SyncReceipt(100, 100),
                    ),
                    keys,
                ),
            )
            when (mismatch) {
                "account" -> assertThrows(Exception::class.java) {
                    checker.verifiedChunk(delivery, part, commit, keys.copy(account = 99), 1)
                }
                "checkpoint" -> assertThrows(Exception::class.java) {
                    checker.verifiedChunk(delivery, part, commit.copy(id = id()), keys, 1)
                }
                "chunk-count" -> assertThrows(Exception::class.java) {
                    checker.verifiedChunk(delivery, part.copy(chunks = 2), commit, keys, 1)
                }
                "child" -> assertThrows(Exception::class.java) {
                    checker.verifiedChunk(delivery, part, commit, keys, 1)
                }
                else -> chunks.forEachIndexed { position, chunk ->
                    assertEquals(chunk, checker.verifiedChunk(delivery, part, commit, keys, position))
                }
            }
        }
        val children = chunks.map { encrypted(syncJson.encodeToString(it)) }
        verify(children)
        for (mismatch in listOf("account", "checkpoint", "chunk-count")) verify(children, mismatch)
        verify(children.toMutableList().also { it[1] = encrypted(syncJson.encodeToString(chunks[1]), 99) }, "child")
    }

    @Test fun packingWaitsForEveryCurrentMemberAndNeverInfersSupportFromAgeOrOwnVersion() {
        val state = mutableMapOf<String, String>()
        every { store.state(any()) } answers { state[firstArg()] }
        assertFalse(checker.packingReady(keys))
        state["packing-capability/1/${device.public.id}"] = "90"
        state["packing-proof/1/${device.public.id}"] = syncJson.encodeToString(
            SyncReaderCapabilities.sign(12, keys.membership, device),
        )
        assertTrue(checker.packingReady(keys))
        val other = SyncDeviceCrypto.generate(id())
        val joined = keys.copy(
            membership = keys.membership.copy(
                devices =
                keys.membership.devices + (other.public.id to other.public),
            ),
        )
        assertFalse(checker.packingReady(joined))
        state["packing-capability/1/${other.public.id}"] = "100"
        state["packing-proof/1/${other.public.id}"] = syncJson.encodeToString(
            SyncReaderCapabilities.sign(12, joined.membership, other),
        )
        assertTrue(checker.packingReady(joined))
        assertFalse(checker.packingReady(joined.copy(membership = joined.membership.copy(epoch = 2))))
        assertFalse(checker.packingReady(keys.copy(membership = keys.membership.copy(devices = emptyMap()))))
    }

    @Test fun portableProofUsesTextAndDoesNotKeepOrRestoreAFullCheckpoint() {
        val state = mutableMapOf<String, String>()
        val retained = mutableMapOf<Long, SyncDelivery>()
        every { store.state(any()) } answers { state[firstArg()] }
        every { store.state(any(), any()) } answers { state[firstArg()] = secondArg() }
        every { files.retainedDelivery(any()) } answers { retained[firstArg()] }
        val proof = SyncReaderCapabilities.sign(12, keys.membership, device)
        val envelope = SyncCipher.encrypt(
            12,
            keys.space,
            1,
            id(),
            keys.key(),
            syncJson.encodeToString(proof).toByteArray(),
        )
        val packet = SyncPacket(
            kind = "capability",
            id = envelope.id,
            space = keys.space,
            payload = syncJson.encodeToString(envelope),
        )
        assertTrue(syncJson.encodeToString(packet).length + SyncEnvelope.MARKER.length < 3600)
        val carrier = SyncDelivery(packet, SyncReceipt(90, 100))
        retained[90] = carrier
        assertTrue(checker.registerCapability(carrier, keys))
        assertEquals(device.public.id, checker.verifiedCapability(carrier, keys)!!.author)
        assertThrows(Exception::class.java) { checker.verifiedCommit(carrier, keys) }
        val commit = SyncCheckpointCommit(
            id = id(), space = keys.space, epoch = 1, author = device.public.id,
            throughMessage = 80, parts = emptyList(), maintenanceVersion = 1,
            packingVersion = 1, readerCapability = proof,
        )
        val signed = SyncCheckpoints.sign(commit, 12, device)
        val daily = SyncCipher.encrypt(
            12,
            keys.space,
            1,
            commit.id,
            keys.key(),
            syncJson.encodeToString(signed).toByteArray(),
        )
        assertTrue(
            checker.registerCapability(
                SyncDelivery(
                    SyncPacket(
                        kind = "commit",
                        id = commit.id,
                        space = keys.space,
                        payload = syncJson.encodeToString(daily),
                    ),
                    SyncReceipt(120, 200),
                ),
                keys,
            ),
        )
        assertEquals("90", state["packing-capability/1/${device.public.id}"])
        assertEquals("90", state["maintenance-capability/1/${device.public.id}"])
    }
}

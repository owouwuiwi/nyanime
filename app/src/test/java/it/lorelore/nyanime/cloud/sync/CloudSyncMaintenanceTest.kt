package it.lorelore.nyanime.cloud.sync

import eu.kanade.tachiyomi.data.cloud.sync.CloudSyncKeys
import eu.kanade.tachiyomi.data.cloud.sync.CloudSyncSession
import eu.kanade.tachiyomi.data.cloud.sync.SyncMaintenanceMessage
import eu.kanade.tachiyomi.data.cloud.sync.SyncMaintenancePage
import eu.kanade.tachiyomi.data.cloud.sync.TelegramSyncTransport
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import nyanime.telegram.TelegramException
import nyanime.telegram.TelegramFailure
import nyanime.telegram.sync.SyncArchiveBootstrap
import nyanime.telegram.sync.SyncBatch
import nyanime.telegram.sync.SyncBatchSignature
import nyanime.telegram.sync.SyncChange
import nyanime.telegram.sync.SyncCheckpointCommit
import nyanime.telegram.sync.SyncCheckpointPart
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.util.UUID

class CloudSyncMaintenanceTest {
    private class Fixture {
        var now = 2_000_000L
        var allowed = true
        val device = SyncDeviceCrypto.generate(UUID.randomUUID().toString())
        val keys = CloudSyncKeys.first(
            12,
            -100,
            SyncArchiveBootstrap.create(12, UUID.randomUUID().toString(), device, SyncRecoveryCode.create()),
            1,
            device,
        )
        val session = mockk<CloudSyncSession>(relaxed = true)
        val transport = mockk<TelegramSyncTransport>(relaxed = true)
        val index = mockk<SyncMaintenanceIndex>()
        val state = mutableMapOf<String, String>()
        fun proof(message: Long) = MaintenanceProof(
            SyncDelivery(
                SyncPacket(
                    kind = "commit",
                    id = UUID.randomUUID().toString(),
                    space = keys.space,
                    payload = "verified",
                ),
                SyncReceipt(message, 1),
            ),
            SyncCheckpointCommit(
                id = UUID.randomUUID().toString(),
                space = keys.space,
                epoch = 1,
                author = device.public.id,
                throughMessage = 80,
                parts = emptyList(),
            ),
        )
        var plan = MaintenancePlan(
            keys.membership.control,
            now,
            listOf(proof(100), proof(120)),
            emptyList(),
            false,
            before = 100,
            verifiedAt = now,
        )
        val change = SyncChange(
            SyncDot(device.public.id, 1),
            SyncVector(),
            SyncRef("item", 31, "/title"),
            "position",
            JsonPrimitive(20),
            100,
        )
        val batch = SyncBatch(
            id = UUID.randomUUID().toString(),
            space = keys.space,
            device = device.public.id,
            changes = listOf(change),
        )
        val data = SyncDelivery(
            SyncPacket.data(
                SyncCipher.encrypt(
                    12,
                    keys.space,
                    1,
                    batch.id,
                    keys.key(),
                    syncJson.encodeToString(SyncBatchSignature.sign(batch, 12, 1, device)).toByteArray(),
                ),
            ),
            SyncReceipt(50, 1),
        )
        val engine get() = CloudSyncMaintenance(session, index, { now }, { allowed })

        init {
            every { session.keys } returns keys
            every { session.device } returns device
            every { session.transport } returns transport
            every { index.value(any()) } answers { state[firstArg()] }
            every { index.put(any(), any()) } answers { state[firstArg()] = secondArg() }
            every { index.plan() } answers { plan }
            every { index.save(any()) } answers { plan = firstArg() }
            every { index.reset(any()) } answers { plan = firstArg() }
            every { index.removed(any()) } answers { state["removed"] = firstArg<Int>().toString() }
            every { index.advance(any(), any()) } answers {
                plan = firstArg()
                state["removed"] = secondArg<Int>().toString()
            }
            every { index.covers(any(), any()) } returns true
            coEvery { transport.maintenancePage(any(), any(), any()) } returns
                SyncMaintenancePage(listOf(50), 30, false)
            coEvery { transport.maintenanceMessage(50) } returns SyncMaintenanceMessage(data, false)
            coEvery { transport.maintenanceDelete(any()) } returns 1
        }
    }

    @Test fun coveredDataIsRemovedOneExactMessageAtATime() = runTest {
        val f = Fixture()
        f.engine.step()
        coVerify(exactly = 1) { f.transport.maintenanceDelete(listOf(f.data)) }
        assertEquals(30L, f.plan.before)
        assertEquals("1", f.state["removed"])
        assertEquals(f.now + 3, f.state["next"]!!.toLong())
    }

    @Test fun aConcurrentChangeMissingFromEitherCheckpointProtectsThePacket() = runTest {
        val f = Fixture()
        every { f.index.covers(any(), any()) } returns false
        f.engine.step()
        coVerify(exactly = 0) { f.transport.maintenanceDelete(any()) }
        assertEquals(30L, f.plan.before)
    }

    @Test fun playbackAndIncognitoGateEveryMaintenanceRequest() = runTest {
        val f = Fixture()
        f.allowed = false
        f.engine.step()
        coVerify(exactly = 0) { f.transport.maintenancePage(any(), any(), any()) }
        assertTrue(f.state.isEmpty())
    }

    @Test fun aLatePlaybackStartPreventsDeletionWithoutAdvancingTheCursor() = runTest {
        val f = Fixture()
        coEvery { f.transport.maintenanceMessage(50) } answers {
            f.allowed = false
            SyncMaintenanceMessage(f.data, false)
        }
        f.engine.step()
        coVerify(exactly = 0) { f.transport.maintenanceDelete(any()) }
        assertEquals(100L, f.plan.before)
    }

    @Test fun uncertainDeletionIsReconciledAfterRestartRatherThanSkippingTheCandidate() = runTest {
        val f = Fixture()
        coEvery { f.transport.maintenanceDelete(any()) } throws CancellationException("Process stopped")
        try {
            f.engine.step()
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(100L, f.plan.before)
        f.now += 31
        coEvery { f.transport.maintenanceMessage(50) } returns null
        f.engine.step()
        assertEquals(30L, f.plan.before)
        assertNull(f.state["removed"])
    }

    @Test fun serverRateLimitSurvivesRestartWithoutBlockingOrdinarySync() = runTest {
        val f = Fixture()
        coEvery { f.transport.maintenancePage(any(), any(), any()) } throws
            TelegramException(TelegramFailure.RATE_LIMIT, retryAfterSeconds = 7200)
        val engine = f.engine
        engine.step()
        assertEquals(SyncMaintenancePhase.RETRY, engine.status.phase)
        assertEquals(f.now + 7200, f.state["next"]!!.toLong())
        f.engine.step()
        coVerify(exactly = 1) { f.transport.maintenancePage(any(), any(), any()) }
        assertEquals(100L, f.plan.before)
    }

    @Test fun aPinnedPacketAndARecentPacketArePreserved() = runTest {
        for (pinned in listOf(true, false)) {
            val f = Fixture()
            coEvery {
                f.transport.maintenanceMessage(50)
            } returns SyncMaintenanceMessage(
                if (pinned) f.data else f.data.copy(receipt = SyncReceipt(50, f.now)),
                pinned,
            )
            f.engine.step()
            coVerify(exactly = 0) { f.transport.maintenanceDelete(any()) }
        }
    }

    @Test fun oldClientsKeepEveryHistoricalCheckpointIntact() = runTest {
        val f = Fixture()
        val checkpoint = f.data.copy(packet = f.data.packet.copy(kind = "commit"))
        coEvery { f.transport.maintenanceMessage(50) } returns SyncMaintenanceMessage(checkpoint, false)
        f.engine.step()
        assertNull(f.plan.candidate)
        coVerify(exactly = 0) { f.transport.enableCheckpointMaintenance() }
        coVerify(exactly = 0) { f.transport.maintenanceDelete(any()) }
    }

    @Test fun anIncompleteCheckpointMustFinishVerificationBeforeAnyDeletion() = runTest {
        val f = Fixture()
        val part = SyncCheckpointPart(UUID.randomUUID().toString(), "0".repeat(64), 90)
        f.plan = f.plan.copy(proofs = f.plan.proofs.map { it.copy(commit = it.commit.copy(parts = listOf(part))) })
        coEvery { f.transport.maintenanceMessage(90) } returns null
        val engine = f.engine
        engine.step()
        assertEquals(SyncMaintenancePhase.RETRY, engine.status.phase)
        coVerify(exactly = 0) { f.transport.maintenancePage(any(), any(), any()) }
        coVerify(exactly = 0) { f.transport.maintenanceDelete(any()) }
    }

    @Test fun aVerifiedPageQueueSurvivesRecreationAndIsNotDownloadedPerCandidate() = runTest {
        val f = Fixture()
        f.plan = f.plan.copy(pendingIds = listOf(50, 40), pageNext = 30, pageComplete = true, examined = 20)
        f.engine.step()
        assertEquals(listOf(40L), f.plan.pendingIds)
        assertEquals(100L, f.plan.before)
        f.now += 4
        coEvery { f.transport.maintenanceMessage(40) } returns null
        f.engine.step()
        assertTrue(f.plan.complete)
        assertEquals(30L, f.plan.before)
        assertEquals(20L, f.plan.examined)
        coVerify(exactly = 0) { f.transport.maintenancePage(any(), any(), any()) }
        coVerify(exactly = 1) { f.transport.maintenanceDelete(any()) }
    }

    @Test fun restoredVerificationCountersDoNotPretendToBeActiveDuringBackoff() = runTest {
        val f = Fixture()
        val part = SyncCheckpointPart(UUID.randomUUID().toString(), "0".repeat(64), 90)
        f.plan =
            f.plan.copy(
                proofs = f.plan.proofs.map {
                    it.copy(commit = it.commit.copy(parts = listOf(part, part)), part = 1)
                },
                indexedChanges = 2048,
                lastActivity = f.now - 100,
            )
        f.state["next"] = (f.now + 20).toString()
        val engine = f.engine
        engine.step()
        assertEquals(2, engine.status.verifiedParts)
        assertEquals(4, engine.status.totalParts)
        assertEquals(2048L, engine.status.indexedChanges)
        assertEquals(f.now - 100, engine.status.lastActivity)
        assertEquals(SyncMaintenanceWait.INTERVAL, engine.status.wait)
        assertTrue(!engine.status.active)
        coVerify(exactly = 0) { f.transport.maintenanceMessage(any()) }
    }

    @Test fun aNewSignedCapabilityReusesVerifiedChunksInsteadOfResettingThem() = runTest {
        val f = Fixture()
        val cap = f.proof(150)
        every { f.session.store.state("maintenance-capability/1/${f.device.public.id}") } returns "150"
        coEvery { f.transport.maintenanceMessage(150) } returns SyncMaintenanceMessage(cap.delivery, false)
        every { f.session.checkpoints.verifiedCommit(cap.delivery, f.keys) } returns
            cap.commit.copy(maintenanceVersion = 1)
        val part = SyncCheckpointPart(UUID.randomUUID().toString(), "0".repeat(64), 90)
        f.plan =
            f.plan.copy(
                proofs = f.plan.proofs.map { it.copy(commit = it.commit.copy(parts = listOf(part)), part = 1) },
                indexedChanges = 2000,
                complete = true,
            )
        f.state["next"] = (f.now + 80_000).toString()
        val engine = f.engine
        engine.step()
        assertTrue(f.plan.removeCheckpoints)
        assertEquals(2000L, f.plan.indexedChanges)
        assertTrue(f.plan.proofs.all { it.part == 1 })
        assertEquals(1, f.plan.checkedAnchors)
        assertEquals(1, engine.status.compatibleDevices)
        io.mockk.verify(exactly = 0) { f.index.reset(any()) }
        coVerify(exactly = 0) { f.transport.maintenanceDelete(any()) }
    }
}

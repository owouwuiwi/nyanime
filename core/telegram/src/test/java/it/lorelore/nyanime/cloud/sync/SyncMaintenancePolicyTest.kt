package it.lorelore.nyanime.cloud.sync

import kotlinx.serialization.json.JsonPrimitive
import nyanime.telegram.sync.SyncArchiveDescriptor
import nyanime.telegram.sync.SyncChange
import nyanime.telegram.sync.SyncDot
import nyanime.telegram.sync.SyncRecord
import nyanime.telegram.sync.SyncRef
import nyanime.telegram.sync.SyncRegister
import nyanime.telegram.sync.SyncVector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.random.Random

class SyncMaintenancePolicyTest {
    private val a = UUID.randomUUID().toString()
    private val b = UUID.randomUUID().toString()
    private val ref = SyncRef("anime-item", 31, "/title", "/episode")
    private fun change(
        device: String = a,
        sequence: Long = 1,
        context: SyncVector = SyncVector(),
        field: String = "position",
        value: Int = 100,
    ) =
        SyncChange(SyncDot(device, sequence), context, ref, field, JsonPrimitive(value), 1)

    @Test fun identicalValuesAreNotCausalAcknowledgements() {
        val first = change()
        assertFalse(SyncMaintenancePolicy.covered(first, SyncRegister().merge(change(b))))
        assertFalse(SyncMaintenancePolicy.covered(first, null))
        assertTrue(SyncMaintenancePolicy.covered(first, SyncRegister().merge(first)))
    }

    @Test fun aLongOfflineRewindMustBeIncludedInBothSnapshots() {
        val watched = change()
        val offline = change(b, value = 10)
        val older = SyncRegister().merge(watched)
        val newer = older.merge(offline)
        assertFalse(listOf(older, newer).all { SyncMaintenancePolicy.covered(offline, it) })
        assertTrue(listOf(newer, newer).all { SyncMaintenancePolicy.covered(offline, it) })
        assertEquals(2, newer.versions.size)
    }

    @Test fun observedRewindsCanSupersedeOldProgressWithoutUsingTheLargestPosition() {
        val first = change()
        val rewind = change(sequence = 2, context = SyncVector(mapOf(a to 1)), value = 5)
        val register = SyncRegister().merge(rewind)
        assertTrue(SyncMaintenancePolicy.covered(first, register))
        assertTrue(SyncMaintenancePolicy.covered(rewind, register))
        assertEquals(JsonPrimitive(5), register.resolved()!!.value)
    }

    @Test fun unrelatedFieldsAndDeletionMarkersCannotBeDiscardedByGlobalVectors() {
        val position = change()
        val bookmark = change(sequence = 2, context = SyncVector(mapOf(a to 1)), field = "bookmark")
        val record = SyncRecord(ref).merge(bookmark)
        assertFalse(SyncMaintenancePolicy.covered(position, record.fields["position"]))
        assertFalse(SyncMaintenancePolicy.covered(position, record.fields["bookmark"]))
        val deleted = position.copy(field = "_exists", value = JsonPrimitive(false))
        assertFalse(SyncMaintenancePolicy.covered(deleted, null))
        assertTrue(SyncMaintenancePolicy.covered(deleted, SyncRegister().merge(deleted)))
    }

    @Test fun reusedRevisionWithChangedContentFailsClosed() {
        val original = change()
        assertThrows(IllegalArgumentException::class.java) {
            SyncMaintenancePolicy.covered(original.copy(value = JsonPrimitive(999)), SyncRegister().merge(original))
        }
    }

    @Test fun sevenDayGracePinnedMessagesAndUnknownDatesAreProtected() {
        val now = 2_000_000L
        val date = now - SyncMaintenancePolicy.MIN_AGE_SECONDS
        assertTrue(SyncMaintenancePolicy.eligible(10, date, now, 10, false))
        assertFalse(SyncMaintenancePolicy.eligible(10, date + 1, now, 10, false))
        assertFalse(SyncMaintenancePolicy.eligible(10, date, now, 9, false))
        assertFalse(SyncMaintenancePolicy.eligible(10, date, now, 10, true))
        assertFalse(SyncMaintenancePolicy.eligible(10, 0, now, 10, false))
        assertFalse(SyncMaintenancePolicy.eligible(10, now + 1, now, 10, false))
    }

    @Test fun allCurrentDevicesMustDeclareSupportIncludingANewMember() {
        assertFalse(SyncMaintenancePolicy.canRemoveCheckpoints(emptySet(), emptyMap()))
        assertFalse(SyncMaintenancePolicy.canRemoveCheckpoints(setOf(a, b), mapOf(a to 1)))
        assertFalse(SyncMaintenancePolicy.canRemoveCheckpoints(setOf(a, b), mapOf(a to 1, b to 0)))
        assertTrue(SyncMaintenancePolicy.canRemoveCheckpoints(setOf(a, b), mapOf(a to 1, b to 1)))
        assertTrue(SyncMaintenancePolicy.canRemoveCheckpoints(setOf(a), mapOf(a to 1, b to 0)))
    }

    @Test fun archiveFenceIsRejectedByLegacyThreeLineParsersAndUnknownFencesFailClosed() {
        val old = SyncArchiveDescriptor(UUID.randomUUID().toString(), 10)
        assertEquals(3, old.encode().lines().size)
        val fenced = old.copy(maintenanceVersion = 1)
        assertEquals(4, fenced.encode().lines().size)
        assertEquals(fenced, SyncArchiveDescriptor.parse(fenced.encode()))
        assertNull(SyncArchiveDescriptor.parse(old.encode() + "\nmaintenance=2"))
    }

    @Test fun randomizedDeliveryAfterCompactionConvergesWithConcurrentAndUnsentEdits() {
        repeat(100) { seed ->
            val random = Random(seed)
            val history = mutableListOf<SyncChange>()
            var av = SyncVector()
            var bv = SyncVector()
            repeat(40) {
                val first = random.nextBoolean()
                val vector = if (first) av else bv
                val device = if (first) a else b
                val edit = change(
                    device,
                    vector[device] + 1,
                    vector,
                    field = if (random.nextBoolean()) "position" else "bookmark",
                    value = random.nextInt(300),
                )
                history += edit
                if (first) av = av.including(edit.dot) else bv = bv.including(edit.dot)
                if (random.nextInt(4) == 0) {
                    av = av.merged(bv)
                    bv = bv.merged(av)
                }
            }
            fun fold(values: List<SyncChange>) = values.fold(SyncRecord(ref)) { result, edit -> result.merge(edit) }
            val checkpoint = fold(history)
            val kept = history.filterNot { SyncMaintenancePolicy.covered(it, checkpoint.fields[it.field]) }
            val offline = change(b, bv[b] + 1, bv, value = 3)
            val pending = change(a, av[a] + 1, av, field = "position", value = 1)
            val expected = fold(history + offline + pending)
            val compacted = checkpoint.fields.values.flatMap { it.versions } + kept + offline + pending
            assertEquals(expected, fold(compacted.shuffled(random)), "Seed $seed")
        }
    }
}

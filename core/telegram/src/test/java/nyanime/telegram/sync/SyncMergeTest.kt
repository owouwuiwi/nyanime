package nyanime.telegram.sync

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncMergeTest {
    private val first = "00000000-0000-0000-0000-000000000001"
    private val second = "00000000-0000-0000-0000-000000000002"
    private val ref = SyncRef("video", 73, "/title/42", "/item/5")

    private fun change(
        device: String = first,
        sequence: Long = 1,
        field: String = "position",
        value: Int = 30,
        context: SyncVector = SyncVector(mapOf(device to sequence - 1)),
        time: Long = 1000,
    ) = SyncChange(SyncDot(device, sequence), context, ref, field, JsonPrimitive(value), time)

    @Test fun rewindWinsItsOwnEarlierProgressRegardlessOfDeliveryOrder() {
        val one = change(value = 1800)
        val two = change(sequence = 2, value = 20, time = 1)
        val a = SyncRegister().merge(one).merge(two)
        val b = SyncRegister().merge(two).merge(one).merge(two)
        assertEquals(a, b)
        assertEquals(JsonPrimitive(20), a.resolved()?.value)
    }

    @Test fun concurrentPositionsArePreservedAndConvergeWithoutTrustingWallClocks() {
        val one = change(value = 90, time = Long.MAX_VALUE)
        val two = change(second, value = 10, time = 0)
        val a = SyncRegister().merge(one).merge(two)
        val b = SyncRegister().merge(two).merge(one)
        assertEquals(a, b)
        assertEquals(2, a.versions.size)
        assertEquals(JsonPrimitive(10), a.resolved()?.value)
    }

    @Test fun observedConflictResolutionReplacesBothAlternatives() {
        val one = change(value = 90)
        val two = change(second, value = 10)
        val resolve = change(
            sequence = 2,
            value = 55,
            context = SyncVector(mapOf(first to 1, second to 1)),
        )
        val events = listOf(one, two, resolve)
        events.permutations().forEach { order ->
            val result = order.fold(SyncRegister()) { register, event -> register.merge(event) }
            assertEquals(listOf(resolve), result.versions)
        }
    }

    @Test fun unrelatedFieldsMergeIndependently() {
        val bookmark = change(field = "bookmark", value = 1)
        val position = change(second, value = 11)
        val record = SyncRecord(ref).merge(bookmark).merge(position)
        assertEquals(JsonPrimitive(1), record.values()["bookmark"])
        assertEquals(JsonPrimitive(11), record.values()["position"])
    }

    @Test fun offlineEditsDoNotResurrectDeletedTitlesButExplicitReaddDoes() {
        val deletion = change(field = SyncRegister.EXISTS).copy(value = JsonPrimitive(false))
        val edit = change(second)
        val staleAdd = change(second, field = SyncRegister.EXISTS).copy(value = JsonPrimitive(true))
        val deleted = SyncRecord(ref).merge(deletion).merge(edit).merge(staleAdd)
        assertTrue(deleted.deleted)
        assertTrue(deleted.values().isEmpty())
        val readd = change(sequence = 2, field = SyncRegister.EXISTS).copy(value = JsonPrimitive(true))
        assertFalse(deleted.merge(readd).deleted)
    }

    @Test fun changingPayloadOfAnExistingRevisionIsRejected() {
        val one = change()
        assertThrows(IllegalArgumentException::class.java) {
            SyncRegister().merge(one).merge(one.copy(value = JsonPrimitive(999)))
        }
    }

    @Test fun concurrentSetRemovalWinsAndAnObservedReadditionRemainsPossible() {
        val key = "member/${SyncCheckpoints.hash("option") }"
        val a = change(field = key).copy(value = null)
        val b = change(second, field = key).copy(value = JsonPrimitive("option"))
        val result = SyncRegister().merge(a).merge(b)
        assertEquals(result, SyncRegister().merge(b).merge(a))
        assertEquals(null, result.resolved()?.value)
        val readd = change(sequence = 2, field = key, context = SyncVector(mapOf(first to 1, second to 1)))
            .copy(value = JsonPrimitive("option"))
        assertEquals(JsonPrimitive("option"), result.merge(readd).resolved()?.value)
    }

    @Test fun stableReferencesUseSourceAndExactUrlsInsteadOfDatabaseIdsOrTitleNames() {
        assertEquals(ref.key(), ref.copy().key())
        assertFalse(ref.key() == ref.copy(source = 74).key())
        assertFalse(ref.key() == ref.copy(item = "/item/6").key())
    }

    private fun <T> List<T>.permutations(): List<List<T>> =
        if (isEmpty()) {
            listOf(emptyList())
        } else {
            flatMap { item ->
                (this - item).permutations().map { listOf(item) + it }
            }
        }
}

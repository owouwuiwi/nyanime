package it.lorelore.nyanime.cloud.sync

import kotlinx.serialization.json.JsonPrimitive
import nyanime.telegram.sync.SyncChange
import nyanime.telegram.sync.SyncDot
import nyanime.telegram.sync.SyncRecord
import nyanime.telegram.sync.SyncRef
import nyanime.telegram.sync.SyncVector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class SyncSupersessionTest {
    private val first = UUID.randomUUID().toString()
    private val second = UUID.randomUUID().toString()
    private val ref = SyncRef("item", 31, "/title", "/chapter")
    private fun change(device: String, sequence: Long, value: Int, context: SyncVector = SyncVector()) =
        SyncChange(
            SyncDot(device, sequence),
            context.copy(counters = context.counters + (device to sequence - 1)),
            ref,
            "position",
            JsonPrimitive(value),
            100,
        )

    @Test fun tenThenTwentyThenThirtyKeepsOnlyThirtyAndNeverReplaysIntermediateValues() {
        val ten = change(first, 1, 10)
        val twenty = change(first, 2, 20)
        val thirty = change(first, 3, 30)
        var record = SyncRecord(ref)
        listOf(ten, twenty, thirty, twenty, ten).forEach { record = record.merge(it) }
        val frontier = record.fields.getValue("position")
        assertEquals(listOf(thirty), frontier.versions)
        assertTrue(SyncMaintenancePolicy.superseded(ten, frontier))
        assertTrue(SyncMaintenancePolicy.superseded(twenty, frontier))
        assertFalse(SyncMaintenancePolicy.superseded(thirty, frontier))
    }

    @Test fun concurrentOfflineEditsAndIndependentFieldsAreNotDiscarded() {
        val ten = change(first, 1, 10)
        val other = change(second, 1, 20)
        val position = SyncRecord(ref).merge(ten).merge(other).fields.getValue("position")
        assertEquals(2, position.versions.size)
        assertFalse(SyncMaintenancePolicy.superseded(ten, position))
        assertFalse(SyncMaintenancePolicy.superseded(other, position))
        assertFalse(SyncMaintenancePolicy.superseded(ten.copy(field = "bookmark"), position))
        assertFalse(SyncMaintenancePolicy.superseded(ten, null))
        val observed = change(first, 2, 30, SyncVector(mapOf(second to 1)))
        val resolved = position.merge(observed)
        assertEquals(listOf(observed), resolved.versions)
        assertTrue(SyncMaintenancePolicy.superseded(other, resolved))
    }
}

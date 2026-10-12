package eu.kanade.tachiyomi.data.rin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RinUpdateScheduleTest {
    private val checked = 10 * 60 * 60 * 1000L

    @Test fun `a known update discovered just after a check is applied without waiting six hours`() {
        assertEquals(
            RinUpdateSchedule.Plan.APPLY_CACHED,
            RinUpdateSchedule.plan(checked + 1, checked, true, false, null),
        )
        assertEquals(RinUpdateSchedule.Plan.IDLE, RinUpdateSchedule.plan(checked + 1, checked, false, false, null))
    }

    @Test fun `a completed or partial refresh is reused instead of causing a second request`() {
        listOf(true, false).forEach { complete ->
            assertEquals(RinUpdateSchedule.Plan.APPLY_CACHED, RinUpdateSchedule.plan(checked, 0, true, false, complete))
        }
    }

    @Test fun `forced due and clock rollback checks refresh the catalogue`() {
        assertEquals(RinUpdateSchedule.Plan.REFRESH, RinUpdateSchedule.plan(checked, checked, false, true, null))
        assertEquals(RinUpdateSchedule.Plan.REFRESH, RinUpdateSchedule.plan(checked, 0, false, false, null))
        assertEquals(RinUpdateSchedule.Plan.REFRESH, RinUpdateSchedule.plan(checked - 1, checked, false, false, null))
        assertEquals(
            RinUpdateSchedule.Plan.REFRESH,
            RinUpdateSchedule.plan(checked + 6 * 60 * 60 * 1000L, checked, false, false, null),
        )
    }
}

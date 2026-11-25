package eu.kanade.presentation.components.releases

import eu.kanade.tachiyomi.data.releases.AiringIssue
import eu.kanade.tachiyomi.data.releases.ReleaseMedium
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReleaseCalendarWarningTest {
    @Test fun animeScheduleFailuresDoNotAppearInTheMangaFilter() {
        val state = ReleaseCalendarScreenModel.State(
            warnings = mapOf(ReleaseMedium.ANIME to AiringIssue.Reason.PENDING),
        )
        assertEquals(AiringIssue.Reason.PENDING, state.warning)
        assertEquals(AiringIssue.Reason.PENDING, state.copy(medium = ReleaseMedium.ANIME).warning)
        assertNull(state.copy(medium = ReleaseMedium.MANGA).warning)
    }

    @Test fun recoveredSnapshotClearsTheWarningInsteadOfKeepingThePreviousFailure() {
        val state = ReleaseCalendarScreenModel.State(
            medium = ReleaseMedium.ANIME,
            warnings = mapOf(ReleaseMedium.ANIME to AiringIssue.Reason.PENDING),
        )
        assertNull(state.copy(warnings = emptyMap()).warning)
    }
}

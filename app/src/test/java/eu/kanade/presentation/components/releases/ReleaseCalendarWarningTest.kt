package eu.kanade.presentation.components.releases

import eu.kanade.tachiyomi.data.releases.ReleaseMedium
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReleaseCalendarWarningTest {
    @Test fun animeScheduleFailuresDoNotAppearInTheMangaFilter() {
        val state = ReleaseCalendarScreenModel.State(warnings = mapOf(ReleaseMedium.ANIME to "Pending verification"))
        assertEquals("Pending verification", state.warning)
        assertEquals("Pending verification", state.copy(medium = ReleaseMedium.ANIME).warning)
        assertNull(state.copy(medium = ReleaseMedium.MANGA).warning)
    }

    @Test fun recoveredSnapshotClearsTheWarningInsteadOfKeepingThePreviousFailure() {
        val state = ReleaseCalendarScreenModel.State(
            medium = ReleaseMedium.ANIME,
            warnings = mapOf(ReleaseMedium.ANIME to "Pending verification"),
        )
        assertNull(state.copy(warnings = emptyMap()).warning)
    }
}

package mihon.feature.upcoming.manga

import androidx.compose.runtime.Composable
import eu.kanade.presentation.components.releases.ReleaseCalendar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.releases.ReleaseMedium

/** Retains restored navigation compatibility while sharing one agenda implementation. */
class UpcomingMangaScreen : Screen() {
    @Composable
    override fun Content() {
        ReleaseCalendar(ReleaseMedium.MANGA)
    }
}

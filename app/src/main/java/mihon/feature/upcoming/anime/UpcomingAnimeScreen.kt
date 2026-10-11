package mihon.feature.upcoming.anime

import androidx.compose.runtime.Composable
import eu.kanade.presentation.components.releases.ReleaseCalendar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.releases.ReleaseMedium

/** Retains restored navigation compatibility while sharing one agenda implementation. */
class UpcomingAnimeScreen : Screen() {
    @Composable
    override fun Content() {
        ReleaseCalendar(ReleaseMedium.ANIME)
    }
}

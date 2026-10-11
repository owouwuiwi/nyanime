package eu.kanade.tachiyomi.ui.releases

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Event
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.components.releases.ReleaseCalendar
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R

data object ReleasesTab : Tab {
    override val options: TabOptions
        @Composable get() = TabOptions(
            2u,
            stringResource(R.string.release_navigation),
            rememberVectorPainter(Icons.Outlined.Event),
        )

    @Composable
    override fun Content() {
        ReleaseCalendar(showBack = false)
    }
}

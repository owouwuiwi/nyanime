package eu.kanade.tachiyomi.ui.explore

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.home.LocalFloatingNavigationInset

data object ExploreTab : Tab {
    override val options: TabOptions
        @Composable get() = TabOptions(
            2u,
            stringResource(R.string.explore_navigation),
            rememberVectorPainter(Icons.Outlined.Explore),
        )

    @Composable
    override fun Content() {
        Box(
            Modifier.fillMaxSize().padding(bottom = LocalFloatingNavigationInset.current),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.explore_coming_soon), style = MaterialTheme.typography.headlineSmall)
        }
    }
}

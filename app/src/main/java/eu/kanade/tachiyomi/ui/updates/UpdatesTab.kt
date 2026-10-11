package eu.kanade.tachiyomi.ui.updates

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.domain.ui.model.NavStyle
import eu.kanade.presentation.components.TabbedScreen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.download.DownloadsTab
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.updates.anime.animeUpdatesTab
import eu.kanade.tachiyomi.ui.updates.manga.mangaUpdatesTab
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import eu.kanade.presentation.util.Screen as AppScreen

data object UpdatesTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_updates_enter)
            val index: UShort = when (currentNavigationStyle()) {
                NavStyle.DISCOVERY -> 6u
                NavStyle.MOVE_UPDATES_TO_MORE -> 5u
                NavStyle.MOVE_HISTORY_TO_MORE -> 2u
                NavStyle.MOVE_BROWSE_TO_MORE -> 2u
                NavStyle.MOVE_MANGA_TO_MORE -> 1u
            }
            return TabOptions(
                index = index,
                title = stringResource(MR.strings.label_recent_updates),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }
    override suspend fun onReselect(navigator: Navigator) {
        navigator.push(DownloadsTab)
    }

    @Composable
    override fun Content() {
        UpdatesContent(initialPage = 0, fromMore = this in currentNavigationStyle().overflowTabs)
    }
}

/** Opens the existing updates view directly on manga when arriving from a manga surface. */
data object MangaUpdatesScreen : AppScreen() {
    @Composable
    override fun Content() {
        UpdatesContent(initialPage = 1, fromMore = true)
    }
}

@Composable
private fun Screen.UpdatesContent(initialPage: Int, fromMore: Boolean) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = initialPage) { 2 }
    TabbedScreen(
        titleRes = MR.strings.label_recent_updates,
        tabs = persistentListOf(
            animeUpdatesTab(context, fromMore),
            mangaUpdatesTab(context, fromMore),
        ),
        state = pagerState,
        pageScopedTheme = true,
    )
    LaunchedEffect(Unit) {
        (context as? MainActivity)?.ready = true
    }
}

package eu.kanade.tachiyomi.ui.history

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.domain.ui.model.NavStyle
import eu.kanade.presentation.components.TabbedScreen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.history.anime.AnimeHistoryScreenModel
import eu.kanade.tachiyomi.ui.history.anime.animeHistoryTab
import eu.kanade.tachiyomi.ui.history.anime.resumeLastEpisodeSeenEvent
import eu.kanade.tachiyomi.ui.history.manga.MangaHistoryScreenModel
import eu.kanade.tachiyomi.ui.history.manga.mangaHistoryTab
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

data object HistoriesTab : Tab {

    private val novelsRequested = MutableStateFlow(false)
    private val selectedPage = MutableStateFlow(TAB_ANIME)

    fun showNovels() {
        novelsRequested.value = true
    }

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_history_enter)
            val index: UShort =
                when (currentNavigationStyle()) {
                    NavStyle.MOVE_HISTORY_TO_MORE -> 5u
                    NavStyle.MOVE_BROWSE_TO_MORE -> 3u
                    else -> 2u
                }
            return TabOptions(
                index = index,
                title = stringResource(MR.strings.history),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        if (selectedPage.value != TAB_NOVEL) resumeLastEpisodeSeenEvent.send(Unit)
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val fromMore = this in currentNavigationStyle().overflowTabs
        // Hoisted for history tab's search bar
        val mangaHistoryScreenModel = rememberScreenModel { MangaHistoryScreenModel() }
        val mangaSearchQuery by mangaHistoryScreenModel.query.collectAsState()

        val animeHistoryScreenModel = rememberScreenModel { AnimeHistoryScreenModel() }
        val animeSearchQuery by animeHistoryScreenModel.query.collectAsState()
        val navigator = LocalNavigator.current
        val scope = rememberCoroutineScope()
        val openNovels by novelsRequested.collectAsState()
        val pager = rememberPagerState(initialPage = if (openNovels) TAB_NOVEL else TAB_ANIME) { 3 }
        LaunchedEffect(openNovels) {
            if (openNovels) {
                pager.scrollToPage(TAB_NOVEL)
                novelsRequested.value = false
            }
        }
        LaunchedEffect(pager) {
            snapshotFlow { pager.settledPage }.collect { selectedPage.value = it }
        }

        TabbedScreen(
            titleRes = MR.strings.label_recent_manga,
            state = pager,
            tabs =
            persistentListOf(
                animeHistoryTab(context, fromMore),
                mangaHistoryTab(context, fromMore),
                eu.kanade.presentation.components.TabContent(
                    titleRes = MR.strings.history,
                    titleOverride =
                    androidx.compose.ui.res.stringResource(R.string.novel_library),
                    privacyArea = eu.kanade.tachiyomi.ui.privacy.PrivacyArea.HISTORY,
                    navigateUp =
                    if (fromMore) {
                        (
                            {
                                if (
                                    navigator?.lastItem ==
                                    eu.kanade.tachiyomi.ui.home.HomeScreen
                                ) {
                                    scope.launch {
                                        eu.kanade.tachiyomi.ui.home.HomeScreen.openTab(
                                            eu.kanade.tachiyomi.ui.home.HomeScreen.Tab.More(
                                                false,
                                            ),
                                        )
                                    }
                                } else {
                                    navigator?.pop()
                                }
                            }
                            )
                    } else {
                        null
                    },
                    content = { padding, _ ->
                        it.lorelore.nyanime.novel.ui.NovelHistoryContent(padding)
                    },
                ),
            ),
            mangaSearchQuery = mangaSearchQuery,
            onChangeMangaSearchQuery = mangaHistoryScreenModel::search,
            animeSearchQuery = animeSearchQuery,
            onChangeAnimeSearchQuery = animeHistoryScreenModel::search,
        )

        LaunchedEffect(Unit) {
            (context as? MainActivity)?.ready = true
        }
    }
}

private const val TAB_ANIME = 0
private const val TAB_MANGA = 1
private const val TAB_NOVEL = 2

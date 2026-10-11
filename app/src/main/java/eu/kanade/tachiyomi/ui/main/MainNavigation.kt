package eu.kanade.tachiyomi.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.NavigatorDisposeBehavior
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.anime.interactor.GetAnimeIncognitoState
import eu.kanade.domain.source.manga.interactor.GetMangaIncognitoState
import eu.kanade.presentation.components.DownloadedOnlyBannerBackgroundColor
import eu.kanade.presentation.components.IncognitoModeBannerBackgroundColor
import eu.kanade.presentation.components.IndexingBannerBackgroundColor
import eu.kanade.presentation.main.MainBannerState
import eu.kanade.presentation.main.MainScaffold
import eu.kanade.presentation.util.DefaultNavigatorScreenTransition
import eu.kanade.tachiyomi.ui.browse.anime.source.browse.BrowseAnimeSourceScreen
import eu.kanade.tachiyomi.ui.browse.manga.source.browse.BrowseMangaSourceScreen
import eu.kanade.tachiyomi.ui.cast.CastMiniController
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.entries.manga.MangaScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.watch.WatchMiniController
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import tachiyomi.presentation.core.util.collectAsState

@Composable
internal fun MainNavigation(
    preferences: BasePreferences,
    mangaIndexing: StateFlow<Boolean>,
    animeIndexing: StateFlow<Boolean>,
    mangaIncognito: GetMangaIncognitoState,
    animeIncognito: GetAnimeIncognitoState,
    needsNavigationScrim: () -> Boolean,
    onSystemBarColors: (statusBar: Color, navigationBar: Color) -> Unit,
    onNavigatorReady: (Navigator) -> Unit,
    navigatorEffects: @Composable (Navigator) -> Unit,
    overlays: @Composable () -> Unit,
) {
    var incognito by remember { mutableStateOf(mangaIncognito.await(null)) }
    var incognitoAnime by remember { mutableStateOf(animeIncognito.await(null)) }
    val downloadedOnly by preferences.downloadedOnly().collectAsState()
    val indexingManga by mangaIndexing.collectAsState()
    val indexingAnime by animeIndexing.collectAsState()
    val banners = MainBannerState(downloadedOnly, incognito || incognitoAnime, indexingManga || indexingAnime)

    val navigationBarColor = MaterialTheme.colorScheme.surface
    val statusBarColor = when {
        banners.indexing -> IndexingBannerBackgroundColor
        banners.downloadedOnly -> DownloadedOnlyBannerBackgroundColor
        banners.incognito -> IncognitoModeBannerBackgroundColor
        else -> navigationBarColor
    }
    LaunchedEffect(statusBarColor, navigationBarColor) {
        onSystemBarColors(statusBarColor, navigationBarColor)
    }

    Box(Modifier.fillMaxSize()) {
        Navigator(
            screen = HomeScreen,
            disposeBehavior = NavigatorDisposeBehavior(disposeNestedNavigators = false, disposeSteps = true),
        ) { navigator ->
            LaunchedEffect(navigator) { onNavigatorReady(navigator) }
            LaunchedEffect(navigator.lastItem) {
                val sourceId = (navigator.lastItem as? BrowseMangaSourceScreen)?.sourceId
                mangaIncognito.subscribe(sourceId).collectLatest { incognito = it }
            }
            LaunchedEffect(navigator.lastItem) {
                val sourceId = (navigator.lastItem as? BrowseAnimeSourceScreen)?.sourceId
                animeIncognito.subscribe(sourceId).collectLatest { incognitoAnime = it }
            }

            MainScaffold(
                banners = banners,
                showNavigationScrim = remember { needsNavigationScrim() },
                bottomBar = {
                    // Home owns the mini controllers alongside its floating navigation.
                    if (navigator.lastItem !is HomeScreen) {
                        Column {
                            WatchMiniController()
                            CastMiniController()
                        }
                    }
                },
            ) { modifier ->
                DefaultNavigatorScreenTransition(navigator, modifier)
            }

            CloseIncognitoDestinations(navigator, preferences)
            navigatorEffects(navigator)
        }
        overlays()
    }
}

@Composable
private fun CloseIncognitoDestinations(navigator: Navigator, preferences: BasePreferences) {
    LaunchedEffect(navigator) {
        preferences.incognitoMode().changes().drop(1).filter { !it }.collect {
            val currentScreen = navigator.lastItem
            val sourceScreen = when (currentScreen) {
                is BrowseMangaSourceScreen, is BrowseAnimeSourceScreen -> true
                is MangaScreen -> currentScreen.fromSource
                is AnimeScreen -> currentScreen.fromSource
                else -> false
            }
            if (sourceScreen) navigator.popUntilRoot()
        }
    }
}

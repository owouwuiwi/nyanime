package eu.kanade.tachiyomi.ui.discovery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.discovery.SourceHomeLogo
import eu.kanade.tachiyomi.data.discovery.MangaHomeCategories
import eu.kanade.tachiyomi.ui.discovery.manga.MangaHomeScreenModel
import eu.kanade.tachiyomi.ui.discovery.manga.MangaHomeSearchScreen
import eu.kanade.tachiyomi.ui.updates.hasNewLibraryUpdateNotice
import eu.kanade.tachiyomi.ui.updates.inboxKey
import eu.kanade.tachiyomi.ui.updates.markLibraryUpdateNoticesSeen
import tachiyomi.domain.discovery.CatalogFeed
import tachiyomi.domain.discovery.SourceHomeGroup
import tachiyomi.domain.discovery.SourceHomeRequest
import tachiyomi.domain.discovery.homePresentation
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Page identity changes independently of the single, persistent Home header. */
internal sealed interface DiscoveryHomePage {
    val key: String

    data object Initializing : DiscoveryHomePage {
        override val key = "initializing"
    }

    data object Empty : DiscoveryHomePage {
        override val key = "empty"
    }

    data class Catalog(val model: DiscoveryScreenModel) : DiscoveryHomePage {
        override val key = "catalog"
    }

    data class Source(val homeKey: String, val model: SourceHomeScreenModel) : DiscoveryHomePage {
        override val key = "source:$homeKey"
    }

    data class Manga(
        val model: MangaHomeScreenModel,
        val homeId: String = MangaHomeCategories.DEFAULT_HOME,
    ) : DiscoveryHomePage {
        override val key = "manga:$homeId"
    }

    data class Tv(val model: eu.kanade.tachiyomi.ui.tv.TvHomeModel) : DiscoveryHomePage {
        override val key = "tv"
    }

    data class News(val model: eu.kanade.tachiyomi.ui.news.NewsScreenModel) : DiscoveryHomePage {
        override val key = "news"
    }
}

@Composable
internal fun DiscoveryTab.rememberHomePage(
    homeKey: String?,
    manga: Boolean,
    initializing: Boolean,
    news: Boolean = false,
    mangaHomeId: String = MangaHomeCategories.DEFAULT_HOME,
    tv: Boolean = false,
    empty: Boolean = false,
): DiscoveryHomePage = when {
    empty -> DiscoveryHomePage.Empty
    tv -> DiscoveryHomePage.Tv(
        rememberScreenModel(tag = "live-tv") {
            val services = Injekt.get<eu.kanade.tachiyomi.data.tv.TvServices>()
            eu.kanade.tachiyomi.ui.tv.TvHomeModel(services.repository, services.adultContent)
        },
    )
    news -> DiscoveryHomePage.News(requireNotNull(eu.kanade.tachiyomi.ui.news.LocalNewsModel.current))
    manga -> DiscoveryHomePage.Manga(
        rememberScreenModel(tag = "reading:$mangaHomeId") { MangaHomeScreenModel(mangaHomeId) },
        mangaHomeId,
    )
    initializing -> DiscoveryHomePage.Initializing
    homeKey != null -> DiscoveryHomePage.Source(
        homeKey,
        rememberScreenModel(tag = homeKey) {
            SourceHomeScreenModel(homeKey)
        },
    )
    else -> DiscoveryHomePage.Catalog(rememberScreenModel { DiscoveryScreenModel() })
}

internal data class DiscoveryHeaderState(
    val onSearch: (() -> Unit)? = null,
    val onRefresh: () -> Unit = {},
    val onUpdates: (() -> Unit)? = null,
    val hasUpdates: Boolean = false,
    val logo: SourceHomeLogo? = null,
    val artworkRefreshKey: Int = 0,
)

/** Derived directly from the selected model; no child registers transient header callbacks. */
@Composable
internal fun DiscoveryHomePage.headerState(homes: List<SourceHomeGroup>): DiscoveryHeaderState {
    val navigator = LocalNavigator.currentOrThrow
    val preference = remember(this is DiscoveryHomePage.Manga) {
        val preferences = Injekt.get<UiPreferences>()
        if (this is DiscoveryHomePage.Manga) {
            preferences.lastSeenMangaUpdateNotice()
        } else {
            preferences.lastSeenAnimeUpdateNotice()
        }
    }
    val seenAt by preference.changes().collectAsState(initial = preference.get())
    var updateKeys: Set<String> = emptySet()
    val header = when (this) {
        is DiscoveryHomePage.Tv -> return DiscoveryHeaderState(onRefresh = { model.refresh(true) })
        is DiscoveryHomePage.News -> return DiscoveryHeaderState(onRefresh = { model.refresh(true) })
        DiscoveryHomePage.Initializing, DiscoveryHomePage.Empty -> DiscoveryHeaderState()
        is DiscoveryHomePage.Catalog -> {
            val state by model.state.collectAsStateWithLifecycle()
            updateKeys = state.updates.data.orEmpty().mapNotNull { it.updateKey }.toSet()
            DiscoveryHeaderState(
                onSearch = { navigator.push(CatalogListScreen(CatalogFeed.SEARCH)) },
                onRefresh = model::refresh,
            )
        }
        is DiscoveryHomePage.Source -> {
            val state by model.state.collectAsStateWithLifecycle()
            // The registry already validated the category; its actions need not disappear during model startup.
            val group = state.access.group ?: homes.firstOrNull { it.id == homeKey }
            updateKeys = state.updates.data.orEmpty().mapNotNull { it.updateKey }.toSet()
            val provider = group?.providers?.takeUnless { state.access.offline }
                ?.distinctBy { it.id }?.singleOrNull()
            val presentation = provider?.let {
                state.sections.values.asSequence().flatMap { it.data?.items.orEmpty().asSequence() }
                    .filter { item -> item.source == it.id }.mapNotNull { item -> item.homePresentation }
                    .firstOrNull { item -> item.logoUrl != null }
            }
            DiscoveryHeaderState(
                onSearch = if (!state.access.offline && group?.searchable == true) {
                    { navigator.push(SourceHomeListScreen(homeKey, SourceHomeRequest.SEARCH, "Cerca ${group.title}")) }
                } else {
                    null
                },
                onRefresh = model::refresh,
                artworkRefreshKey = state.artworkRefreshKey,
                logo = if (provider != null && presentation?.logoUrl != null) {
                    SourceHomeLogo(
                        provider.id,
                        provider.sourceName,
                        requireNotNull(presentation.logoUrl),
                        presentation.logoName,
                        presentation.logoBackground,
                    )
                } else {
                    null
                },
            )
        }
        is DiscoveryHomePage.Manga -> {
            val state by model.state.collectAsStateWithLifecycle()
            updateKeys = state.updates.map { it.inboxKey() }.toSet()
            DiscoveryHeaderState(
                onSearch = { navigator.push(MangaHomeSearchScreen(homeId = homeId)) },
                onRefresh = model::refresh,
            )
        }
    }
    return header.copy(
        hasUpdates = hasNewLibraryUpdateNotice(updateKeys, seenAt),
        onUpdates = {
            markLibraryUpdateNoticesSeen(preference, updateKeys)
            DiscoveryTab.revealUpdates(key)
        },
    )
}

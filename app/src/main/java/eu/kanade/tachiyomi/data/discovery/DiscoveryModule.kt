package eu.kanade.tachiyomi.data.discovery

import android.app.Application
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import eu.kanade.domain.entries.anime.model.toSAnime
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.data.library.anime.AnimeRefreshSchedule
import eu.kanade.tachiyomi.data.track.AnimeMangaContinuity
import eu.kanade.tachiyomi.data.track.ContinuityIdentityCache
import eu.kanade.tachiyomi.data.track.ContinuitySourcePolicy
import eu.kanade.tachiyomi.data.track.MangaAnimeContinuity
import eu.kanade.tachiyomi.network.NetworkHelper
import tachiyomi.data.discovery.CachedAnimeCatalogRepository
import tachiyomi.data.discovery.DiscoveryDatabase
import tachiyomi.data.discovery.SqlDiscoveryStore
import tachiyomi.domain.discovery.AnimeCatalogCache
import tachiyomi.domain.discovery.AnimeCatalogRemote
import tachiyomi.domain.discovery.AnimeCatalogRepository
import tachiyomi.domain.discovery.AnimeSourceLinkRepository
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get

class DiscoveryModule(private val app: Application) : InjektModule {
    override fun InjektRegistrar.registerInjectables() {
        addSingletonFactory { eu.kanade.tachiyomi.data.search.SmartTitleSearch(app) }
        addSingletonFactory<tachiyomi.domain.search.TitleSearch> {
            get<eu.kanade.tachiyomi.data.search.SmartTitleSearch>()
        }
        addSingletonFactory {
            DiscoveryDatabase(AndroidSqliteDriver(DiscoveryDatabase.Schema, app, "discovery.db"))
        }
        addSingletonFactory { SqlDiscoveryStore(get()) }
        addSingletonFactory<AnimeCatalogCache> { PrivateDiscoveryCache(get<SqlDiscoveryStore>(), get()) }
        addSingletonFactory<AnimeSourceLinkRepository> { get<SqlDiscoveryStore>() }
        addSingletonFactory<AnimeCatalogRemote> {
            val client = get<NetworkHelper>().apiClient
            tachiyomi.domain.discovery.FailoverAnimeCatalogRemote(
                AnilistCatalogRemote(client, get()),
                KitsuCatalogRemote(client, get()),
            )
        }
        addSingletonFactory<AnimeCatalogRepository> { CachedAnimeCatalogRepository(get(), get(), get()) }
        addSingletonFactory<ExtensionHomeManifestReader> { ApkExtensionHomeManifestReader(app) }
        addSingletonFactory<MangaHomeManifestReader> { ApkMangaHomeManifestReader(app) }
        addSingletonFactory { MangaHomeRegistry(get(), get(), get(), get(), get(), get(), get()) }
        addSingletonFactory { MangaHomeService(get(), get(), get()) }
        addSingletonFactory { ContinuityIdentityCache(get()) }
        addSingletonFactory { ContinuitySourcePolicy(get(), get(), get(), get()) }
        addSingletonFactory { AnimeMangaContinuity(get(), get(), get(), get(), get(), get()) }
        addSingletonFactory { MangaAnimeContinuity(get(), get(), get(), get(), get(), get()) }
        addSingletonFactory { ExtensionHomeRegistry(get(), get(), get(), get(), get(), get(), get()) }
        addSingletonFactory<tachiyomi.domain.discovery.SourceHomeCache> {
            tachiyomi.data.discovery.SqlSourceHomeCache(get(), get())
        }
        addSingletonFactory { ExtensionHomeServices(get(), get(), get(), get()) }
        addSingletonFactory {
            val manager = get<AnimeSourceManager>()
            SourceHomeArtworkResolver(fetch = { anime ->
                val source = requireNotNull(manager.get(anime.source))
                val requested = anime.toSAnime()
                val details = if (anime.fetchType == FetchType.Seasons) {
                    source.getAnimeSeasonUpdate(requested, emptyList(), fetchDetails = true, fetchSeasons = false).anime
                } else {
                    source.getAnimeEpisodeUpdate(
                        requested,
                        emptyList(),
                        fetchDetails = true,
                        fetchEpisodes = false,
                    ).anime
                }
                SourceHomeArtworkResolver.Artwork(details.thumbnail_url, details.background_url)
            })
        }
        addSingletonFactory {
            DiscoverySourceService(get(), get(), get(), get(), get(), get(), get(), get(), get(), get())
        }
        addSingletonFactory<EpisodeEndingCueStore> { SqlEpisodeEndingCueStore(get()) }
        addSingletonFactory { NearEndingCompletion(get(), get(), get(), get(), get(), get(), get(), app) }
        addSingletonFactory { ResumeEpisodeSelector(get(), get(), get(), get()) }
        addSingletonFactory { DiscoveryPlaybackService(get(), get(), get()) }
        addSingletonFactory { tachiyomi.domain.discovery.CatalogSeriesEvidence(get()) }
        addSingletonFactory { SmartSourceResolver(get(), get(), get(), get(), get(), get(), get()) }
        addSingletonFactory { ResumeVisibility(get()) }
        addSingletonFactory { AnimeRefreshSchedule(get()) }
        addSingletonFactory { ContinueWatchingRefresher(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        addSingletonFactory {
            LocalHomeSections { resume, sourceId ->
                LocalHomeSectionProvider(
                    get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(),
                    resume, sourceId, get(),
                )
            }
        }
    }
}

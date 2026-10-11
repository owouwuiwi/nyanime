package nyanime.extension.api.layer

import android.graphics.Bitmap
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimeRelation
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.HttpServer
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeEpisodeUpdate
import eu.kanade.tachiyomi.animesource.model.SAnimeSeasonUpdate
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.ThumbnailInfo
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.HttpUrl

interface AnimeLayerFactoryV1 {
    fun attach(source: AnimeCatalogueSource): AnimeLayerV1
}

class AnimeLayerLinkV1(val anime: SAnime, val episode: SEpisode? = null)

/** An unimplemented operation delegates to the authenticated base source. */
interface AnimeLayerV1 {
    fun filters(): AnimeFilterList? = null
    fun supportsLatest(): Boolean? = null
    suspend fun popular(page: Int): AnimesPage? = null
    suspend fun latest(page: Int): AnimesPage? = null
    suspend fun search(page: Int, query: String, filters: AnimeFilterList): AnimesPage? = null

    suspend fun episodeUpdate(
        anime: SAnime,
        episodes: List<SEpisode>,
        fetchDetails: Boolean,
        fetchEpisodes: Boolean,
    ): SAnimeEpisodeUpdate? = null

    suspend fun seasonUpdate(
        anime: SAnime,
        seasons: List<SAnime>,
        fetchDetails: Boolean,
        fetchSeasons: Boolean,
    ): SAnimeSeasonUpdate? = null

    fun supportsRelated(): Boolean? = null
    suspend fun related(anime: SAnime): List<AnimeRelation>? = null

    suspend fun byUrl(url: HttpUrl): AnimeLayerLinkV1? = null
    suspend fun byCatalogId(namespace: String, id: Long): SAnime? = null
    suspend fun relatedMangaLinks(animeUrl: String): List<String>? = null
    fun homeUrl(): String? = null
    fun animeUrl(anime: SAnime): String? = null
    fun episodeUrl(episode: SEpisode): String? = null

    suspend fun hosters(episode: SEpisode): List<Hoster>? = null
    suspend fun hosterVideos(hoster: Hoster): List<Video>? = null
    suspend fun episodeVideos(episode: SEpisode): List<Video>? = null
    suspend fun resolveVideo(video: Video): Video? = null
    suspend fun videoUrl(video: Video): String? = null
    fun httpServer(): HttpServer? = null
    suspend fun videoThumbnails(video: Video): ThumbnailInfo? = null
    suspend fun imageTile(url: String): Bitmap? = null
}

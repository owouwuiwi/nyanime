package nyanime.extension.internal

import android.graphics.Bitmap
import eu.kanade.tachiyomi.animesource.AnimeCatalogIdResolver
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.RelatedMangaLinks
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.HttpServer
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.ThumbnailInfo
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.coroutines.CancellationException
import nyanime.extension.api.layer.AnimeLayerV1
import okhttp3.HttpUrl
import java.util.UUID

/** Host calls select approved operations without replacing the source instance. */
object AnimeLayerDispatch {
    @Volatile
    private var bindings: Map<AnimeSource, Map<String, Binding>> = emptyMap()

    fun publish(next: Map<AnimeSource, Map<String, Binding>>) {
        bindings = next
    }

    fun current(source: AnimeSource, capability: String): Binding? =
        bindings[source]?.get(capability)?.takeUnless { it.incompatible }

    class Binding(
        private val layer: AnimeLayerV1,
        private val failed: () -> Unit,
    ) {
        internal val playbackToken = UUID.randomUUID().toString()

        @Volatile
        var incompatible = false
            private set

        private fun reject() {
            incompatible = true
            failed()
        }

        fun <T> call(block: AnimeLayerV1.() -> T?): T? {
            if (incompatible) return null
            return try {
                layer.block()
            } catch (_: LinkageError) {
                reject()
                null
            }
        }

        suspend fun <T> await(block: suspend AnimeLayerV1.() -> T?): T? {
            if (incompatible) return null
            return try {
                layer.block()
            } catch (_: LinkageError) {
                reject()
                null
            }
        }

        suspend fun <T> play(block: suspend AnimeLayerV1.() -> T?): T? {
            check(!incompatible) { "The source layer changed. Reopen the episode to retry." }
            return try {
                layer.block()
            } catch (failure: LinkageError) {
                reject()
                throw IllegalStateException("The source layer changed. Reopen the episode to retry.", failure)
            }
        }
    }
}

fun AnimeSource.layerFilters(): AnimeFilterList =
    AnimeLayerDispatch.current(this, "catalog")?.call { filters() } ?: getFilterList()

fun AnimeSource.layerSupportsLatest() =
    AnimeLayerDispatch.current(this, "catalog")?.call { supportsLatest() } ?: supportsLatest

suspend fun AnimeSource.layerPopular(page: Int) =
    AnimeLayerDispatch.current(this, "catalog")?.await { popular(page) } ?: getPopularAnime(page)

suspend fun AnimeSource.layerLatest(page: Int) =
    AnimeLayerDispatch.current(this, "catalog")?.await { latest(page) } ?: getLatestUpdates(page)

suspend fun AnimeSource.layerSearch(page: Int, query: String, filters: AnimeFilterList) =
    AnimeLayerDispatch.current(this, "catalog")?.await { search(page, query, filters) }
        ?: getSearchAnime(page, query, filters)

suspend fun AnimeSource.layerEpisodeUpdate(
    anime: SAnime,
    episodes: List<SEpisode>,
    fetchDetails: Boolean,
    fetchEpisodes: Boolean,
) = AnimeLayerDispatch.current(this, "update")?.await { episodeUpdate(anime, episodes, fetchDetails, fetchEpisodes) }
    ?: getAnimeEpisodeUpdate(anime, episodes, fetchDetails, fetchEpisodes)

suspend fun AnimeSource.layerSeasonUpdate(
    anime: SAnime,
    seasons: List<SAnime>,
    fetchDetails: Boolean,
    fetchSeasons: Boolean,
) = AnimeLayerDispatch.current(this, "update")?.await { seasonUpdate(anime, seasons, fetchDetails, fetchSeasons) }
    ?: getAnimeSeasonUpdate(anime, seasons, fetchDetails, fetchSeasons)

fun AnimeSource.layerSupportsRelated() =
    AnimeLayerDispatch.current(this, "update")?.call { supportsRelated() } ?: supportsRelatedAnime

suspend fun AnimeSource.layerRelated(
    anime: SAnime,
    exceptionHandler: (Throwable) -> Unit,
    pushResults: suspend (Pair<String, List<SAnime>>, Boolean) -> Unit,
) {
    val groups = try {
        AnimeLayerDispatch.current(this, "update")?.await { related(anime) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        exceptionHandler(error)
        return
    }
    if (groups == null) {
        getRelatedAnimeList(anime, exceptionHandler, pushResults)
    } else {
        groups.forEach { pushResults(it.name to it.animes, true) }
    }
}

suspend fun AnimeSource.layerByUrl(url: HttpUrl) =
    AnimeLayerDispatch.current(this, "identity")?.await { byUrl(url) }

suspend fun AnimeSource.layerByCatalogId(namespace: String, id: Long) =
    AnimeLayerDispatch.current(this, "identity")?.await { byCatalogId(namespace, id) }
        ?: (this as? AnimeCatalogIdResolver)?.findAnimeByCatalogId(namespace, id)

suspend fun AnimeSource.layerRelatedMangaLinks(animeUrl: String) =
    AnimeLayerDispatch.current(this, "identity")?.await { relatedMangaLinks(animeUrl) }
        ?: (this as? RelatedMangaLinks)?.relatedMangaLinks(animeUrl)
        ?: emptyList()

fun AnimeHttpSource.layerHomeUrl() =
    AnimeLayerDispatch.current(this, "identity")?.call { homeUrl() } ?: getHomeUrl()

fun AnimeHttpSource.layerAnimeUrl(anime: SAnime) =
    AnimeLayerDispatch.current(this, "identity")?.call { animeUrl(anime) } ?: getAnimeUrl(anime)

fun AnimeHttpSource.layerEpisodeUrl(episode: SEpisode) =
    AnimeLayerDispatch.current(this, "identity")?.call { episodeUrl(episode) } ?: getEpisodeUrl(episode)

/** Carries the layer that produced an opaque playback descriptor to its next step. */
internal class AnimeLayerPlayback(
    val source: AnimeSource,
    val binding: AnimeLayerDispatch.Binding,
) {
    fun record(hoster: Hoster) {
        hoster.layerPlayback = this
        hoster.videoList?.forEach(::record)
    }

    fun record(video: Video) {
        video.layerPlayback = this
    }
}

private fun AnimeSource.playerBinding() = AnimeLayerDispatch.current(this, "player")

private fun AnimeSource.checkPlayback(owner: AnimeLayerPlayback) {
    check(owner.source === this) { "The source changed. Reopen the episode to retry." }
}

suspend fun AnimeSource.layerReplacementHosters(episode: SEpisode): List<Hoster>? {
    val binding = playerBinding() ?: return null
    val selected = binding.await { hosters(episode) ?: episodeVideos(episode)?.toHosterList() } ?: return null
    val owner = AnimeLayerPlayback(this, binding)
    return selected.onEach(owner::record)
}

suspend fun AnimeSource.layerHosterVideos(hoster: Hoster): List<Video> {
    hoster.layerPlayback?.let { owner ->
        checkPlayback(owner)
        val videos = owner.binding.play { hosterVideos(hoster) }
            ?: error("The source layer did not provide videos for this hoster.")
        return videos.onEach(owner::record)
    }
    val binding = playerBinding() ?: return getVideoList(hoster)
    val videos = binding.await { hosterVideos(hoster) } ?: return getVideoList(hoster)
    val owner = AnimeLayerPlayback(this, binding)
    return videos.onEach(owner::record)
}

suspend fun AnimeHttpSource.layerResolveVideo(video: Video): Video? {
    video.layerPlayback?.let { owner ->
        checkPlayback(owner)
        return owner.binding.play { resolveVideo(video) }?.also(owner::record)
            ?: video.takeIf { it.videoUrl.isNotBlank() && it.videoUrl != "null" }
    }
    val binding = playerBinding() ?: return resolveVideo(video)
    return binding.await { resolveVideo(video) }?.also(AnimeLayerPlayback(this, binding)::record)
        ?: resolveVideo(video)
}

suspend fun AnimeHttpSource.layerVideoWithUrl(video: Video): Video {
    video.layerPlayback?.let { owner ->
        checkPlayback(owner)
        val url = owner.binding.play { videoUrl(video) } ?: error("The source layer did not provide the video URL.")
        return video.copy(videoUrl = url).also(owner::record)
    }
    val binding = playerBinding()
    val url = binding?.await { videoUrl(video) }
    return if (url != null) {
        video.copy(videoUrl = url).also(AnimeLayerPlayback(this, requireNotNull(binding))::record)
    } else {
        video.copy(videoUrl = getVideoUrl(video))
    }
}

fun Video.layerInitializedCopy(): Video = copy(initialized = true).also { it.layerPlayback = layerPlayback }

fun Hoster.layerCopyWithVideos(videos: List<Video>): Hoster =
    copy(videoList = videos, lazy = false).also { it.layerPlayback = layerPlayback }

/** Restore inline and lazy hoster ownership after passage through a player intent. */
fun AnimeSource.layerRestoreHosters(hosters: List<Hoster>): List<Hoster>? {
    val tokens = hosters.mapNotNull(Hoster::layerTransfer).distinct()
    if (tokens.isEmpty()) return hosters
    val binding = playerBinding() ?: return null
    if (tokens.singleOrNull() != binding.playbackToken) return null
    val owner = AnimeLayerPlayback(this, binding)
    hosters.filter { it.layerTransfer != null }.forEach(owner::record)
    return hosters
}

fun AnimeHttpSource.layerSortVideos(videos: List<Video>): List<Video> =
    if (videos.any { it.layerPlayback != null }) videos else videos.sortVideos()

fun AnimeHttpSource.layerHttpServer(video: Video? = null): HttpServer? {
    video?.layerPlayback?.let { owner ->
        checkPlayback(owner)
        return owner.binding.call { httpServer() }
    }
    return playerBinding()?.call { httpServer() } ?: createHttpServer()
}

suspend fun AnimeHttpSource.layerVideoThumbnails(video: Video): ThumbnailInfo? {
    video.layerPlayback?.let { owner ->
        checkPlayback(owner)
        return owner.binding.play { videoThumbnails(video) }
    }
    return playerBinding()?.await { videoThumbnails(video) } ?: getVideoThumbnails(video)
}

suspend fun AnimeHttpSource.layerImageTile(url: String, video: Video? = null): Bitmap? {
    video?.layerPlayback?.let { owner ->
        checkPlayback(owner)
        return owner.binding.play { imageTile(url) }
    }
    return playerBinding()?.await { imageTile(url) } ?: getImageTile(url)
}

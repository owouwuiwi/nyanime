package eu.kanade.tachiyomi.data.track

import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.track.service.TrackPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.manga.interactor.GetManga
import tachiyomi.domain.history.anime.interactor.GetAnimeHistory
import tachiyomi.domain.history.manga.interactor.GetMangaHistory
import tachiyomi.domain.items.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.items.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.source.manga.service.MangaSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap

/** Links a newly saved library title without waiting for playback or reader startup. */
internal object LibraryAutoTracking {
    private data class Request(val key: String, val delayMs: Long, val attempt: Int = 0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<Request>(Channel.UNLIMITED)
    private val pending = ConcurrentHashMap.newKeySet<String>()

    init {
        scope.launch {
            for (request in requests) {
                var retry = false
                try {
                    if (request.delayMs > 0) delay(request.delayMs)
                    retry = !process(request.key)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    logcat(LogPriority.WARN, error) { "Could not link a new library title" }
                    retry = true
                } finally {
                    pending.remove(request.key)
                }
                if (retry) {
                    AutoTrackingRetry.delayAfter(request.attempt)?.let { pause ->
                        scope.launch {
                            delay(pause)
                            if (request.key in pendingPreference().get()) {
                                enqueue(request.key, delayMs = 0, attempt = request.attempt + 1)
                            }
                        }
                    }
                }
            }
        }
    }

    fun animeAdded(id: Long) = add("a:$id")

    fun mangaAdded(id: Long) = add("m:$id")

    /** Replays additions interrupted by process death without repeating completed attempts. */
    fun onForeground() {
        if (!canRun()) return
        val services = Injekt.get<TrackerManager>().loggedInTrackers()
        pendingPreference().get()
            .filter { supported(it, services) }
            .forEach { enqueue(it, delayMs = 0) }
    }

    private fun canRun() = !Injekt.get<BasePreferences>().incognitoMode().get() &&
        Injekt.get<TrackPreferences>().autoUpdateTrack().get()

    private fun supported(key: String, services: List<Tracker>) = when (key.substringBefore(':')) {
        "a" -> services.any { it is AnimeTracker }
        "m" -> services.any { it is MangaTracker }
        else -> false
    }

    private fun pendingPreference() = Injekt.get<PreferenceStore>()
        .getStringSet(Preference.appStateKey("library_tracking_pending_v1"))

    @Synchronized
    private fun add(key: String) {
        val preference = pendingPreference()
        preference.set(preference.get() + key)
        if (canRun() && supported(key, Injekt.get<TrackerManager>().loggedInTrackers())) {
            enqueue(key, delayMs = 1_000)
        }
    }

    @Synchronized
    private fun finish(key: String) {
        val preference = pendingPreference()
        preference.set(preference.get() - key)
    }

    private suspend fun trackAnime(id: Long): Boolean {
        val anime = Injekt.get<GetAnime>().await(id)?.takeIf { it.favorite } ?: return true
        if (Injekt.get<eu.kanade.domain.source.anime.interactor.GetAnimeIncognitoState>().await(
                anime.source,
            )
        ) {
            return true
        }
        val sources = Injekt.get<AnimeSourceManager>()
        if (withTimeoutOrNull(15_000) { sources.isInitialized.first { it } } != true) return false
        val source = sources.get(anime.source) ?: return false
        val historyEpisodeIds = Injekt.get<GetAnimeHistory>().await(id).mapTo(mutableSetOf()) { it.episodeId }
        val episodeNumber = Injekt.get<GetEpisodesByAnimeId>().await(id)
            .filter { (it.seen || it.id in historyEpisodeIds) && it.episodeNumber > 0 && it.episodeNumber.isFinite() }
            .maxOfOrNull { it.episodeNumber } ?: 0.0
        return !AutoTrackOnStart.animeAttempt(anime, source, episodeNumber).retryable
    }

    private suspend fun trackManga(id: Long): Boolean {
        val manga = Injekt.get<GetManga>().await(id)?.takeIf { it.favorite } ?: return true
        if (Injekt.get<eu.kanade.domain.source.manga.interactor.GetMangaIncognitoState>().await(
                manga.source,
            )
        ) {
            return true
        }
        val sources = Injekt.get<MangaSourceManager>()
        if (withTimeoutOrNull(15_000) { sources.isInitialized.first { it } } != true) return false
        val source = sources.get(manga.source) ?: return false
        val started = Injekt.get<GetChaptersByMangaId>().await(id).any { it.read } ||
            Injekt.get<GetMangaHistory>().await(id).isNotEmpty()
        return !AutoTrackOnStart.mangaAttempt(manga, source, started = started).retryable
    }

    private fun enqueue(key: String, delayMs: Long, attempt: Int = 0) {
        if (!pending.add(key)) return
        requests.trySend(Request(key, delayMs, attempt))
    }

    private suspend fun process(key: String): Boolean {
        if (!canRun() || !supported(key, Injekt.get<TrackerManager>().loggedInTrackers())) return true
        val id = key.substringAfter(':', "").toLongOrNull()
        val attempted = when (key.substringBefore(':')) {
            "a" -> id?.let { trackAnime(it) }
            "m" -> id?.let { trackManga(it) }
            else -> true
        }
        if (attempted != false) finish(key)
        delay(750)
        return attempted != false
    }
}

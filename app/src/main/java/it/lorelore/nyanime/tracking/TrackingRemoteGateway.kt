package it.lorelore.nyanime.tracking

import android.content.Context
import eu.kanade.tachiyomi.data.track.AnimeTracker
import eu.kanade.tachiyomi.data.track.MangaTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.track.anime.interactor.GetAnimeTracks
import tachiyomi.domain.track.anime.model.AnimeTrack
import tachiyomi.domain.track.anime.repository.AnimeTrackRepository
import tachiyomi.domain.track.manga.interactor.GetMangaTracks
import tachiyomi.domain.track.manga.model.MangaTrack
import tachiyomi.domain.track.manga.repository.MangaTrackRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import eu.kanade.domain.track.anime.model.toDbTrack as toAnimeDb
import eu.kanade.domain.track.anime.model.toDomainTrack as toAnimeDomain
import eu.kanade.domain.track.manga.model.toDbTrack as toMangaDb
import eu.kanade.domain.track.manga.model.toDomainTrack as toMangaDomain

data class TrackingStatusChoice(val value: Long, val label: String, val tone: PersonalTrackingStatus?)
data class TrackingScoreChoice(val value: Double, val label: String, val tenPoint: Double)

data class RemoteTrackingTarget(
    val tracker: Tracker,
    val subject: TrackingSubject,
    val entryId: Long,
    val trackId: Long,
    val remoteId: Long,
    val account: String,
    val values: TrackingValues,
    val total: Long,
    val statuses: List<TrackingStatusChoice>,
    val scores: List<TrackingScoreChoice>,
    val formattedScore: String,
) {
    val key: String get() = TrackingEditGate.key(subject.medium == TrackingMedium.MANGA, trackId)
}

fun AnimeTrack.trackingValues() = TrackingValues(status, lastEpisodeSeen, score, startDate, finishDate, private)
fun MangaTrack.trackingValues() = TrackingValues(status, lastChapterRead, score, startDate, finishDate, private)

private fun AnimeTrack.withValues(values: TrackingValues) = copy(
    status = values.status,
    lastEpisodeSeen = values.progress,
    score = values.score,
    startDate = values.startDate,
    finishDate = values.finishDate,
    private = values.private,
)
private fun MangaTrack.withValues(values: TrackingValues) = copy(
    status = values.status,
    lastChapterRead = values.progress,
    score = values.score,
    startDate = values.startDate,
    finishDate = values.finishDate,
    private = values.private,
)

class TrackingRemoteGateway(private val context: Context) {
    private val store get() = PersonalTrackingStore.get(context)

    suspend fun send(edit: PendingTrackingEdit) {
        val service = Injekt.get<TrackerManager>().get(edit.trackerId) ?: error("Tracker unavailable")
        check(service.isLoggedIn && service.getUsername() == edit.account) { "Tracker account changed" }
        if (edit.subject.medium == TrackingMedium.ANIME) {
            check(service is AnimeTracker)
            val current = Injekt.get<GetAnimeTracks>().awaitOne(edit.trackId) ?: error("Tracking entry removed")
            check(
                current.animeId == edit.entryId &&
                    current.remoteId == edit.remoteId &&
                    current.trackerId == edit.trackerId,
            )
            val remote = service.refresh(current.toAnimeDb()).toAnimeDomain() ?: error("Invalid tracking response")
            check(service.isLoggedIn && service.getUsername() == edit.account && remote.remoteId == edit.remoteId)
            val desired = remote.withValues(edit.patch.applyTo(remote.trackingValues()))
                .copy(id = current.id, animeId = current.animeId, trackerId = current.trackerId)
            val response = service.update(desired.toAnimeDb()).toAnimeDomain() ?: error("Invalid tracking response")
            check(response.remoteId == edit.remoteId && service.getUsername() == edit.account)
            check(Injekt.get<GetAnimeTracks>().awaitOne(edit.trackId)?.remoteId == edit.remoteId) {
                "Tracking association changed"
            }
            Injekt.get<AnimeTrackRepository>().insertAnime(
                response.copy(id = current.id, animeId = current.animeId, trackerId = current.trackerId),
            )
        } else {
            check(service is MangaTracker)
            val current = Injekt.get<GetMangaTracks>().awaitOne(edit.trackId) ?: error("Tracking entry removed")
            check(
                current.mangaId == edit.entryId &&
                    current.remoteId == edit.remoteId &&
                    current.trackerId == edit.trackerId,
            )
            val remote = service.refresh(current.toMangaDb()).toMangaDomain() ?: error("Invalid tracking response")
            check(service.isLoggedIn && service.getUsername() == edit.account && remote.remoteId == edit.remoteId)
            val desired = remote.withValues(edit.patch.applyTo(remote.trackingValues()))
                .copy(id = current.id, mangaId = current.mangaId, trackerId = current.trackerId)
            val response = service.update(desired.toMangaDb()).toMangaDomain() ?: error("Invalid tracking response")
            check(response.remoteId == edit.remoteId && service.getUsername() == edit.account)
            check(Injekt.get<GetMangaTracks>().awaitOne(edit.trackId)?.remoteId == edit.remoteId) {
                "Tracking association changed"
            }
            Injekt.get<MangaTrackRepository>().insertManga(
                response.copy(id = current.id, mangaId = current.mangaId, trackerId = current.trackerId),
            )
        }
    }

    suspend fun drain(): Boolean {
        var failed = false
        for (snapshot in store.pending()) {
            TrackingEditGate.entry(snapshot.key).mutex.withLock {
                // Drain edits accepted during the request without waiting for WorkManager backoff.
                // The bound also prevents continuous editing from monopolising one worker.
                repeat(8) {
                    val current = store.pending(snapshot.key) ?: return@withLock
                    try {
                        val exists = if (current.subject.medium == TrackingMedium.MANGA) {
                            Injekt.get<GetMangaTracks>().awaitOne(current.trackId) != null
                        } else {
                            Injekt.get<GetAnimeTracks>().awaitOne(current.trackId) != null
                        }
                        if (!exists) {
                            // Explicitly unlinking a tracker also abandons its unsent edits.
                            store.complete(current)
                            return@repeat
                        }
                        send(current)
                        store.complete(current)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        store.fail(current)
                        failed = true
                        return@withLock
                    }
                }
            }
        }
        return !failed && store.pending().isEmpty()
    }
}

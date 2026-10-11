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
    val accountEpoch: String = "",
    val titleHint: String = "",
    val remoteUrl: String = "",
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

class TrackingRemoteGateway(
    private val context: Context,
    private val storeOverride: PersonalTrackingStore? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val store get() = storeOverride ?: PersonalTrackingStore.get(context)
    private val manager get() = Injekt.get<TrackerManager>()

    private suspend fun checkIdentity(edit: PendingTrackingEdit, service: Tracker) {
        if (!service.isLoggedIn) throw TrackingQueueException(TrackingQueueProblem.AUTHENTICATION)
        if (service.getUsername() != edit.account ||
            TrackingAccountBinding.enhanced(service) &&
            edit.accountEpoch.isBlank() ||
            edit.accountEpoch.isNotBlank() &&
            TrackingAccountBinding.current(service, edit.subject) != edit.accountEpoch
        ) {
            throw TrackingQueueException(TrackingQueueProblem.ACCOUNT)
        }
        val valid = if (edit.subject.medium == TrackingMedium.ANIME) {
            val title = Injekt.get<tachiyomi.domain.entries.anime.interactor.GetAnime>().await(edit.entryId)
            val track = Injekt.get<GetAnimeTracks>().awaitOne(edit.trackId)
            title != null &&
                title.source == edit.subject.source &&
                title.url == edit.subject.url &&
                track != null &&
                track.animeId == edit.entryId &&
                track.trackerId == edit.trackerId &&
                track.remoteId == edit.remoteId &&
                (edit.remoteUrl.isBlank() || track.remoteUrl == edit.remoteUrl)
        } else {
            val title = Injekt.get<tachiyomi.domain.entries.manga.interactor.GetManga>().await(edit.entryId)
            val track = Injekt.get<GetMangaTracks>().awaitOne(edit.trackId)
            title != null &&
                title.source == edit.subject.source &&
                title.url == edit.subject.url &&
                track != null &&
                track.mangaId == edit.entryId &&
                track.trackerId == edit.trackerId &&
                track.remoteId == edit.remoteId &&
                (edit.remoteUrl.isBlank() || track.remoteUrl == edit.remoteUrl)
        }
        if (!valid) throw TrackingQueueException(TrackingQueueProblem.ASSOCIATION)
        val isPrivate = Injekt.get<eu.kanade.domain.base.BasePreferences>().incognitoMode().get() ||
            if (edit.subject.medium == TrackingMedium.MANGA) {
                Injekt.get<eu.kanade.domain.source.manga.interactor.GetMangaIncognitoState>().await(edit.subject.source)
            } else {
                Injekt.get<eu.kanade.domain.source.anime.interactor.GetAnimeIncognitoState>().await(edit.subject.source)
            }
        if (isPrivate) throw TrackingQueueException(TrackingQueueProblem.PRIVACY)
    }

    suspend fun send(edit: PendingTrackingEdit): TrackingConfirmation {
        val service = manager.get(edit.trackerId) ?: throw TrackingQueueException(TrackingQueueProblem.ASSOCIATION)
        checkIdentity(edit, service)
        val fingerprint = {
            TrackingRequestGuard.fingerprint(
                (service as? eu.kanade.tachiyomi.data.track.BaseTracker)?.queueCredentialFingerprint()
                    ?: TrackingRequestGuard.fingerprint(service.getUsername(), service.getPassword()),
                TrackingAccountBinding.current(service, edit.subject),
            )
        }
        return providerGate(edit.trackerId).withLock {
            TrackingRequestGuard.leased(service.id, fingerprint) {
                kotlinx.coroutines.withTimeout(45_000) {
                    if (edit.subject.medium ==
                        TrackingMedium.ANIME
                    ) {
                        sendAnime(edit, service)
                    } else {
                        sendManga(edit, service)
                    }
                }
            }
        }
    }

    private suspend fun sendAnime(edit: PendingTrackingEdit, service: Tracker): TrackingConfirmation {
        if (service !is AnimeTracker) throw TrackingQueueException(TrackingQueueProblem.ASSOCIATION)
        val current = Injekt.get<GetAnimeTracks>().awaitOne(edit.trackId)
            ?: throw TrackingQueueException(TrackingQueueProblem.ASSOCIATION)
        val remote = service.refresh(current.toAnimeDb()).toAnimeDomain()
            ?: throw TrackingQueueException(TrackingQueueProblem.REJECTED)
        checkIdentity(edit, service)
        if (remote.remoteId != edit.remoteId) throw TrackingQueueException(TrackingQueueProblem.ASSOCIATION)
        store.noteAttemptBase(edit, remote.trackingValues())
        val values = try {
            TrackingQueueReconciliation.desired(edit, remote.trackingValues())
        } catch (conflict: TrackingQueueException) {
            if (conflict.problem == TrackingQueueProblem.CONFLICT) {
                Injekt.get<AnimeTrackRepository>().updateIfUnchanged(
                    current,
                    remote.copy(id = current.id, animeId = current.animeId, trackerId = current.trackerId),
                )
            }
            throw conflict
        }
        val desired = remote.withValues(
            values,
        ).copy(id = current.id, animeId = current.animeId, trackerId = current.trackerId)
        val response = if (values == remote.trackingValues()) {
            desired
        } else {
            service.update(
                desired.toAnimeDb(),
                TrackingQueuePolicy.automaticTransitions(edit) && values.progress > remote.lastEpisodeSeen,
            )
                .toAnimeDomain() ?: throw TrackingQueueException(TrackingQueueProblem.REJECTED)
        }
        checkIdentity(edit, service)
        if (response.remoteId != edit.remoteId) throw TrackingQueueException(TrackingQueueProblem.ASSOCIATION)
        Injekt.get<AnimeTrackRepository>().updateIfUnchanged(
            current,
            response.copy(
                id = current.id,
                animeId = current.animeId,
                trackerId = current.trackerId,
            ),
        )
        return TrackingConfirmation(remote.trackingValues(), response.trackingValues())
    }

    private suspend fun sendManga(edit: PendingTrackingEdit, service: Tracker): TrackingConfirmation {
        if (service !is MangaTracker) throw TrackingQueueException(TrackingQueueProblem.ASSOCIATION)
        val current = Injekt.get<GetMangaTracks>().awaitOne(edit.trackId)
            ?: throw TrackingQueueException(TrackingQueueProblem.ASSOCIATION)
        val remote = service.refresh(current.toMangaDb()).toMangaDomain()
            ?: throw TrackingQueueException(TrackingQueueProblem.REJECTED)
        checkIdentity(edit, service)
        if (remote.remoteId != edit.remoteId) throw TrackingQueueException(TrackingQueueProblem.ASSOCIATION)
        store.noteAttemptBase(edit, remote.trackingValues())
        val values = try {
            TrackingQueueReconciliation.desired(edit, remote.trackingValues())
        } catch (conflict: TrackingQueueException) {
            if (conflict.problem == TrackingQueueProblem.CONFLICT) {
                Injekt.get<MangaTrackRepository>().updateIfUnchanged(
                    current,
                    remote.copy(id = current.id, mangaId = current.mangaId, trackerId = current.trackerId),
                )
            }
            throw conflict
        }
        val desired = remote.withValues(
            values,
        ).copy(id = current.id, mangaId = current.mangaId, trackerId = current.trackerId)
        val response = if (values == remote.trackingValues()) {
            desired
        } else {
            service.update(
                desired.toMangaDb(),
                TrackingQueuePolicy.automaticTransitions(edit) && values.progress > remote.lastChapterRead,
            )
                .toMangaDomain() ?: throw TrackingQueueException(TrackingQueueProblem.REJECTED)
        }
        checkIdentity(edit, service)
        if (response.remoteId != edit.remoteId) throw TrackingQueueException(TrackingQueueProblem.ASSOCIATION)
        Injekt.get<MangaTrackRepository>().updateIfUnchanged(
            current,
            response.copy(
                id = current.id,
                mangaId = current.mangaId,
                trackerId = current.trackerId,
            ),
        )
        return TrackingConfirmation(remote.trackingValues(), response.trackingValues())
    }

    suspend fun drain(): Boolean = drain { send(it) }

    suspend fun drain(sender: suspend (PendingTrackingEdit) -> TrackingConfirmation): Boolean {
        val started = now()
        val cooldown = mutableSetOf<Long>()
        val eligible = store.pending().asSequence()
            .filter { TrackingQueuePolicy.ready(it, started) && store.cooldown(it.trackerId) <= started }
            .sortedBy { it.createdAt }
            .take(TrackingQueuePolicy.MAX_PASS)
            .toList()
        for (snapshot in eligible) {
            if (now() - started > 60_000) break
            if (snapshot.trackerId in cooldown ||
                !TrackingQueuePolicy.ready(snapshot, now())
            ) {
                continue
            }
            if (store.cooldown(snapshot.trackerId) > now()) continue
            TrackingEditGate.entry(snapshot.key).mutex.withLock {
                val edit = store.claim(snapshot, now()) ?: return@withLock
                try {
                    val confirmed = sender(edit)
                    store.complete(edit, confirmed)
                } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                    store.failed(edit, TrackingQueueProblem.NETWORK, now())
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    store.failed(edit, TrackingQueueProblem.NETWORK, now())
                    throw cancelled
                } catch (error: Exception) {
                    val failure = TrackingQueueFailures.classify(error)
                    store.failed(edit, failure.first, now(), failure.second)
                    if (failure.first == TrackingQueueProblem.RATE_LIMIT) {
                        cooldown += edit.trackerId
                        store.cooldown(
                            edit.trackerId,
                            now() + (failure.second ?: 60_000).coerceIn(30_000, TrackingQueuePolicy.MAX_RETRY_AFTER),
                        )
                    }
                }
            }
        }
        return store.pending().none { it.delivery != TrackingDelivery.BLOCKED }
    }

    companion object {
        private val providers = java.util.concurrent.ConcurrentHashMap<Long, kotlinx.coroutines.sync.Mutex>()
        private fun providerGate(id: Long) = providers.getOrPut(id) { kotlinx.coroutines.sync.Mutex() }
    }
}

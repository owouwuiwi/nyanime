package eu.kanade.domain.track.anime.interactor

import android.content.Context
import eu.kanade.domain.track.anime.model.toDbTrack
import eu.kanade.domain.track.anime.model.toDomainTrack
import eu.kanade.domain.track.anime.service.DelayedAnimeTrackingUpdateJob
import eu.kanade.domain.track.anime.store.DelayedAnimeTrackingStore
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.util.system.isOnline
import it.lorelore.nyanime.tracking.PersonalTrackingStore
import it.lorelore.nyanime.tracking.TrackingEditGate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.track.anime.interactor.GetAnimeTracks
import tachiyomi.domain.track.anime.interactor.InsertAnimeTrack

class TrackEpisode(
    private val getTracks: GetAnimeTracks,
    private val trackerManager: TrackerManager,
    private val insertTrack: InsertAnimeTrack,
    private val delayedTrackingStore: DelayedAnimeTrackingStore,
) {

    suspend fun await(context: Context, animeId: Long, episodeNumber: Double, setupJobOnFailure: Boolean = true) {
        withNonCancellableContext {
            it.lorelore.nyanime.tracking.PersonalTrackingObserver.observe(context, false, animeId, episodeNumber)
            val tracks = getTracks.await(animeId)
            if (tracks.isEmpty()) return@withNonCancellableContext

            tracks.mapNotNull { track ->
                val service = trackerManager.get(track.trackerId)
                if (service == null || !service.isLoggedIn || episodeNumber <= track.lastEpisodeSeen) {
                    return@mapNotNull null
                }

                val gateKey = TrackingEditGate.key(false, track.id)
                val gate = TrackingEditGate.entry(gateKey)
                val generation = gate.progressGeneration.get()
                val account = service.getUsername()
                async {
                    gate.mutex.withLock {
                        if (generation != gate.progressGeneration.get() ||
                            service.getUsername() != account
                        ) {
                            return@withLock Result.success(Unit)
                        }
                        val pending = PersonalTrackingStore.get(context).pending(gateKey)
                        if (pending != null) {
                            if (pending.patch.fields.none { field ->
                                    field ==
                                        it.lorelore.nyanime.tracking.TrackingField.PROGRESS ||
                                        field == it.lorelore.nyanime.tracking.TrackingField.STATUS
                                }
                            ) {
                                delayedTrackingStore.addAnime(track.id, episodeNumber)
                                if (setupJobOnFailure) DelayedAnimeTrackingUpdateJob.setupTask(context)
                            }
                            return@withLock Result.success(Unit)
                        }
                        val current = getTracks.awaitOne(track.id) ?: return@withLock Result.success(Unit)
                        if (episodeNumber <= current.lastEpisodeSeen) return@withLock Result.success(Unit)
                        runCatching {
                            if (context.isOnline()) {
                                val updatedTrack = service.animeService.refresh(current.toDbTrack())
                                    .toDomainTrack(idRequired = true)!!
                                    .copy(lastEpisodeSeen = episodeNumber)
                                if (generation != gate.progressGeneration.get() ||
                                    service.getUsername() != account ||
                                    PersonalTrackingStore.get(context).pending(gateKey)?.patch?.fields?.any { field ->
                                        field == it.lorelore.nyanime.tracking.TrackingField.PROGRESS ||
                                            field == it.lorelore.nyanime.tracking.TrackingField.STATUS
                                    } == true
                                ) {
                                    return@withLock Result.success(Unit)
                                }
                                service.animeService.update(updatedTrack.toDbTrack(), true)
                                insertTrack.await(updatedTrack)
                                delayedTrackingStore.removeAnimeItem(track.id)
                            } else {
                                delayedTrackingStore.addAnime(track.id, episodeNumber)
                                if (setupJobOnFailure) {
                                    DelayedAnimeTrackingUpdateJob.setupTask(context)
                                }
                            }
                        }
                    }
                }
            }
                .awaitAll()
                .mapNotNull { it.exceptionOrNull() }
                .forEach { logcat(LogPriority.INFO, it) }
        }
    }
}

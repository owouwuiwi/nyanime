package eu.kanade.domain.track.anime.interactor

import eu.kanade.domain.track.anime.model.toDbTrack
import eu.kanade.domain.track.anime.model.toDomainTrack
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import it.lorelore.nyanime.tracking.PersonalTrackingStore
import it.lorelore.nyanime.tracking.TrackingEditGate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.track.anime.interactor.GetAnimeTracks
import tachiyomi.domain.track.anime.interactor.InsertAnimeTrack
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class RefreshAnimeTracks(
    private val getTracks: GetAnimeTracks,
    private val trackerManager: TrackerManager,
    private val insertTrack: InsertAnimeTrack,
    private val syncEpisodeProgressWithTrack: SyncEpisodeProgressWithTrack,
) {

    /**
     * Fetches updated tracking data from all logged in trackers.
     *
     * @return Failed updates.
     */
    suspend fun await(animeId: Long): List<Pair<Tracker?, Throwable>> {
        return supervisorScope {
            return@supervisorScope getTracks.await(animeId)
                .map { it to trackerManager.get(it.trackerId) }
                .filter { (_, service) -> service?.isLoggedIn == true }
                .map { (track, service) ->
                    val gateKey = TrackingEditGate.key(false, track.id)
                    val gate = TrackingEditGate.entry(gateKey)
                    val generation = gate.generation.get()
                    val account = service!!.getUsername()
                    async {
                        gate.mutex.withLock {
                            if (generation != gate.generation.get() ||
                                PersonalTrackingStore.get(Injekt.get<android.app.Application>()).pending(gateKey) !=
                                null
                            ) {
                                return@withLock null
                            }
                            try {
                                val updatedTrack = service!!.animeService.refresh(track.toDbTrack()).toDomainTrack()!!
                                if (generation != gate.generation.get() ||
                                    service.getUsername() != account ||
                                    PersonalTrackingStore.get(Injekt.get<android.app.Application>()).pending(gateKey) !=
                                    null ||
                                    getTracks.awaitOne(track.id)?.remoteId != track.remoteId
                                ) {
                                    return@withLock null
                                }
                                insertTrack.await(updatedTrack)
                                syncEpisodeProgressWithTrack.await(animeId, updatedTrack, service.animeService)
                                null
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (e: Exception) {
                                service to e
                            }
                        }
                    }
                }
                .awaitAll()
                .filterNotNull()
        }
    }
}

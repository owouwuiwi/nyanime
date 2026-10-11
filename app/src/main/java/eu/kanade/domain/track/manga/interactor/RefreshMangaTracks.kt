package eu.kanade.domain.track.manga.interactor

import eu.kanade.domain.track.manga.model.toDbTrack
import eu.kanade.domain.track.manga.model.toDomainTrack
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import it.lorelore.nyanime.tracking.PersonalTrackingStore
import it.lorelore.nyanime.tracking.TrackingEditGate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.track.manga.interactor.GetMangaTracks
import tachiyomi.domain.track.manga.interactor.InsertMangaTrack
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class RefreshMangaTracks(
    private val getTracks: GetMangaTracks,
    private val trackerManager: TrackerManager,
    private val insertTrack: InsertMangaTrack,
    private val syncChapterProgressWithTrack: SyncChapterProgressWithTrack,
) {

    /**
     * Fetches updated tracking data from all logged in trackers.
     *
     * @return Failed updates.
     */
    suspend fun await(mangaId: Long): List<Pair<Tracker?, Throwable>> {
        return supervisorScope {
            return@supervisorScope getTracks.await(mangaId)
                .map { it to trackerManager.get(it.trackerId) }
                .filter { (_, service) -> service?.isLoggedIn == true }
                .map { (track, service) ->
                    val gateKey = TrackingEditGate.key(true, track.id)
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
                                val updatedTrack = service!!.mangaService.refresh(track.toDbTrack()).toDomainTrack()!!
                                if (generation != gate.generation.get() ||
                                    service.getUsername() != account ||
                                    PersonalTrackingStore.get(Injekt.get<android.app.Application>()).pending(gateKey) !=
                                    null ||
                                    getTracks.awaitOne(track.id)?.remoteId != track.remoteId
                                ) {
                                    return@withLock null
                                }
                                insertTrack.await(updatedTrack)
                                syncChapterProgressWithTrack.await(mangaId, updatedTrack, service.mangaService)
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

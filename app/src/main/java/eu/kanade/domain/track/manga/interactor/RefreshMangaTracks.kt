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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class RefreshMangaTracks(
    private val getTracks: GetMangaTracks,
    private val trackerManager: TrackerManager,
    private val syncChapterProgressWithTrack: SyncChapterProgressWithTrack,
) {

    /**
     * Fetches updated tracking data from all logged in trackers.
     *
     * @return Failed updates.
     */
    suspend fun await(mangaId: Long): List<Pair<Tracker?, Throwable>> {
        return supervisorScope {
            val entry = Injekt.get<tachiyomi.domain.entries.manga.interactor.GetManga>().await(mangaId)
                ?: return@supervisorScope emptyList()
            val subject = it.lorelore.nyanime.tracking.TrackingSubject(
                it.lorelore.nyanime.tracking.TrackingMedium.MANGA,
                entry.source,
                entry.url,
            )
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
                                val epoch = it.lorelore.nyanime.tracking.TrackingAccountBinding.current(
                                    service,
                                    subject,
                                )
                                val updatedTrack = service!!.mangaService.refresh(track.toDbTrack()).toDomainTrack()!!
                                if (generation != gate.generation.get() ||
                                    service.getUsername() != account ||
                                    it.lorelore.nyanime.tracking.TrackingAccountBinding.current(service, subject) !=
                                    epoch ||
                                    PersonalTrackingStore.get(Injekt.get<android.app.Application>()).pending(gateKey) !=
                                    null ||
                                    getTracks.awaitOne(track.id)?.remoteId != track.remoteId
                                ) {
                                    return@withLock null
                                }
                                if (!Injekt.get<tachiyomi.domain.track.manga.repository.MangaTrackRepository>()
                                        .updateIfUnchanged(track, updatedTrack)
                                ) {
                                    return@withLock null
                                }
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

package eu.kanade.domain.track.manga.interactor

import android.content.Context
import eu.kanade.domain.track.manga.model.toDbTrack
import eu.kanade.domain.track.manga.model.toDomainTrack
import eu.kanade.domain.track.manga.service.DelayedMangaTrackingUpdateJob
import eu.kanade.domain.track.manga.store.DelayedMangaTrackingStore
import eu.kanade.tachiyomi.data.track.TrackerManager
import it.lorelore.nyanime.tracking.PersonalTrackingStore
import it.lorelore.nyanime.tracking.TrackingEditGate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.track.manga.interactor.GetMangaTracks
import tachiyomi.domain.track.manga.interactor.InsertMangaTrack

class TrackChapter(
    private val getTracks: GetMangaTracks,
    private val trackerManager: TrackerManager,
    private val insertTrack: InsertMangaTrack,
    private val delayedTrackingStore: DelayedMangaTrackingStore,
) {

    suspend fun await(context: Context, mangaId: Long, chapterNumber: Double, setupJobOnFailure: Boolean = true) {
        withNonCancellableContext {
            it.lorelore.nyanime.tracking.PersonalTrackingObserver.observe(context, true, mangaId, chapterNumber)
            val tracks = getTracks.await(mangaId)
            if (tracks.isEmpty()) return@withNonCancellableContext

            tracks.mapNotNull { track ->
                val service = trackerManager.get(track.trackerId)
                if (service == null || !service.isLoggedIn || chapterNumber <= track.lastChapterRead) {
                    return@mapNotNull null
                }

                val gateKey = TrackingEditGate.key(true, track.id)
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
                                delayedTrackingStore.addManga(track.id, chapterNumber)
                                if (setupJobOnFailure) DelayedMangaTrackingUpdateJob.setupTask(context)
                            }
                            return@withLock Result.success(Unit)
                        }
                        val current = getTracks.awaitOne(track.id) ?: return@withLock Result.success(Unit)
                        if (chapterNumber <= current.lastChapterRead) return@withLock Result.success(Unit)
                        runCatching {
                            try {
                                val updatedTrack = service.mangaService.refresh(current.toDbTrack())
                                    .toDomainTrack(idRequired = true)!!
                                    .copy(lastChapterRead = chapterNumber)
                                if (generation != gate.progressGeneration.get() ||
                                    service.getUsername() != account ||
                                    PersonalTrackingStore.get(context).pending(gateKey)?.patch?.fields?.any { field ->
                                        field == it.lorelore.nyanime.tracking.TrackingField.PROGRESS ||
                                            field == it.lorelore.nyanime.tracking.TrackingField.STATUS
                                    } == true
                                ) {
                                    return@withLock Result.success(Unit)
                                }
                                service.mangaService.update(updatedTrack.toDbTrack(), true)
                                insertTrack.await(updatedTrack)
                                delayedTrackingStore.removeMangaItem(track.id)
                            } catch (e: Exception) {
                                delayedTrackingStore.addManga(track.id, chapterNumber)
                                if (setupJobOnFailure) {
                                    DelayedMangaTrackingUpdateJob.setupTask(context)
                                }
                                throw e
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

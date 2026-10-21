package eu.kanade.domain.track.manga.interactor

import android.app.Application
import eu.kanade.tachiyomi.data.track.EnhancedMangaTracker
import eu.kanade.tachiyomi.data.track.MangaTracker
import it.lorelore.nyanime.tracking.TrackingAutomaticProgress
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.items.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.items.chapter.interactor.UpdateChapter
import tachiyomi.domain.items.chapter.model.toChapterUpdate
import tachiyomi.domain.track.manga.model.MangaTrack
import kotlin.math.max

class SyncChapterProgressWithTrack(
    private val updateChapter: UpdateChapter,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val app: Application,
) {

    suspend fun await(
        mangaId: Long,
        remoteTrack: MangaTrack,
        tracker: MangaTracker,
    ) {
        if (tracker !is EnhancedMangaTracker) {
            return
        }

        val sortedChapters = getChaptersByMangaId.await(mangaId)
            .sortedBy { it.chapterNumber }
            .filter { it.isRecognizedNumber }

        val chapterUpdates = sortedChapters
            .filter { chapter -> chapter.chapterNumber <= remoteTrack.lastChapterRead && !chapter.read }
            .map { it.copy(read = true).toChapterUpdate() }

        // only take into account continuous reading
        val localLastRead = sortedChapters.takeWhile { it.read }.lastOrNull()?.chapterNumber ?: 0F
        val lastRead = max(remoteTrack.lastChapterRead, localLastRead.toDouble())

        try {
            updateChapter.awaitAll(chapterUpdates)
            TrackingAutomaticProgress.enqueue(
                app,
                true,
                mangaId,
                lastRead,
                trackerId = (tracker as eu.kanade.tachiyomi.data.track.Tracker).id,
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e)
        }
    }
}

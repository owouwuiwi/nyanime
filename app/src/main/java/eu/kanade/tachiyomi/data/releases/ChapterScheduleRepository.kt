package eu.kanade.tachiyomi.data.releases

import eu.kanade.tachiyomi.source.ReleaseScheduleProvider
import eu.kanade.tachiyomi.source.ScheduledRelease
import kotlinx.coroutines.CancellationException
import tachiyomi.data.handlers.manga.MangaDatabaseHandler
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.source.manga.service.MangaSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ChapterScheduleRepository(private val db: MangaDatabaseHandler = Injekt.get()) {
    data class Event(val entryId: Long, val release: ScheduledRelease)

    fun events() = db.subscribeToList {
        chapterScheduleQueries.getSchedule { id, number, at, name -> Event(id, ScheduledRelease(number, at, name)) }
    }

    suspend fun refresh(manga: Manga) {
        val provider = Injekt.get<MangaSourceManager>().get(manga.source) as? ReleaseScheduleProvider ?: return
        try {
            val items = provider.getReleaseSchedule(manga.url)
            require(items.all { it.number.isFinite() && it.number > 0 && it.releaseAt > 0 })
            db.await(inTransaction = true) {
                chapterScheduleQueries.removeSchedule(manga.id)
                items.distinctBy { it.number }.forEach {
                    chapterScheduleQueries.insertRelease(manga.id, it.number, it.releaseAt, it.name)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep the previous announced dates when the extension is temporarily unavailable.
        }
    }
}

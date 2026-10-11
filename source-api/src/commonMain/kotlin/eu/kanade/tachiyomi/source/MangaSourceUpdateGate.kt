package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Extensions may reject concurrent updates of the same title. Keep every caller on one path. */
object MangaSourceUpdateGate {
    private val locks = Array(256) { Mutex() }

    suspend fun await(
        source: MangaSource,
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val slot = (31 * source.id.hashCode() + manga.url.hashCode()) and (locks.size - 1)
        return locks[slot].withLock {
            source.getMangaUpdate(manga, chapters, fetchDetails, fetchChapters)
        }
    }
}

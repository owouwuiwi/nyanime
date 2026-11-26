package eu.kanade.domain.items.chapter.interactor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tachiyomi.domain.items.chapter.repository.ChapterRepository

class GetAvailableScanlators(
    private val repository: ChapterRepository,
) {

    /** The empty key counts chapters for which the source did not supply a group. */
    suspend fun await(mangaId: Long): Map<String, Int> {
        return repository.getScanlatorsByMangaId(mangaId)
            .groupingBy { it }.eachCount()
    }

    fun subscribe(mangaId: Long): Flow<Map<String, Int>> {
        return repository.getScanlatorsByMangaIdAsFlow(mangaId)
            .map { scanlators -> scanlators.groupingBy { it }.eachCount() }
    }
}

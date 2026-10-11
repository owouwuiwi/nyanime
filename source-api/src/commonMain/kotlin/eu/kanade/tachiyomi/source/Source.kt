package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate

/** Manga extension API 1.6. The older MangaSource contract remains available for installed extensions. */
interface Source : MangaSource {
    val supportsLatest: Boolean

    fun getFilterList(): FilterList

    suspend fun getPopularManga(page: Int): MangasPage

    suspend fun getLatestUpdates(page: Int): MangasPage

    suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = super<MangaSource>.getMangaUpdate(manga, chapters, fetchDetails, fetchChapters)

    override suspend fun getPageList(chapter: SChapter): List<Page> = super<MangaSource>.getPageList(chapter)
}

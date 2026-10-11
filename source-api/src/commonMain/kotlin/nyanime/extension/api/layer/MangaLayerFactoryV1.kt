package nyanime.extension.api.layer

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import okhttp3.HttpUrl
import okhttp3.Response

/** The source passed to [attach] is the original source, with no layer dispatch. */
interface MangaLayerFactoryV1 {
    fun attach(source: CatalogueSource): MangaLayerV1
}

/** Optional version information for layers with a fix that only applies to specific releases. */
interface VersionedMangaLayerFactoryV1 : MangaLayerFactoryV1 {
    fun attach(source: CatalogueSource, versionCode: Long): MangaLayerV1

    override fun attach(source: CatalogueSource): MangaLayerV1 = attach(source, 0)
}

/**
 * Independent operations of a manga layer. A null result delegates to the original source.
 * The host only invokes operations in the capabilities authorized for this package.
 */
interface MangaLayerV1 {
    fun filters(): FilterList? = null
    suspend fun popular(page: Int): MangasPage? = null
    suspend fun latest(page: Int): MangasPage? = null
    suspend fun search(page: Int, query: String, filters: FilterList): MangasPage? = null
    suspend fun update(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate? = null
    suspend fun related(manga: SManga): List<SManga>? = null
    suspend fun byUrl(url: HttpUrl): SManga? = null
    suspend fun byCatalogId(namespace: String, id: Long): SManga? = null
    suspend fun byCatalogLink(url: String): SManga? = null
    fun mangaUrl(manga: SManga): String? = null
    fun chapterUrl(chapter: SChapter): String? = null
    suspend fun pages(chapter: SChapter): List<Page>? = null
    suspend fun imageUrl(page: Page): String? = null
    suspend fun image(page: Page): Response? = null
}

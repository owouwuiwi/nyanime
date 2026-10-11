package nyanime.extension.internal

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.MangaCatalogIdResolver
import eu.kanade.tachiyomi.source.MangaCatalogLinkResolver
import eu.kanade.tachiyomi.source.MangaSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import eu.kanade.tachiyomi.source.model.MangasPage
import nyanime.extension.api.layer.MangaLayerV1

/** Host bindings retain the original source; the layer's ABI surfaces no registered source. */
object MangaLayerDispatch {
    @Volatile
    private var bindings: Map<MangaSource, Map<String, Binding>> = emptyMap()

    fun publish(next: Map<MangaSource, Map<String, Binding>>) {
        bindings = next
    }

    fun current(source: MangaSource, capability: String): Binding? =
        bindings[source]?.get(capability)?.takeUnless { it.incompatible }

    class Binding(
        private val layer: MangaLayerV1,
        private val failed: () -> Unit,
    ) {
        @Volatile
        var incompatible = false
            private set

        private fun reject() {
            incompatible = true
            failed()
        }

        fun <T> call(block: MangaLayerV1.() -> T?): T? {
            if (incompatible) return null
            return try {
                layer.block()
            } catch (_: LinkageError) {
                reject()
                null
            }
        }

        suspend fun <T> await(block: suspend MangaLayerV1.() -> T?): T? {
            if (incompatible) return null
            return try {
                layer.block()
            } catch (_: LinkageError) {
                reject()
                null
            }
        }

        suspend fun <T> read(block: suspend MangaLayerV1.() -> T?): T? {
            check(!incompatible) { "The source layer changed. Reopen the chapter to retry." }
            return try {
                layer.block()
            } catch (failure: LinkageError) {
                reject()
                throw IllegalStateException("The source layer changed. Reopen the chapter to retry.", failure)
            } catch (failure: ReaderLayerContract) {
                reject()
                throw IllegalStateException("The source layer cannot read this chapter. Reopen it to retry.", failure)
            }
        }
    }
}

fun CatalogueSource.layerFilters(): FilterList =
    MangaLayerDispatch.current(this, "catalog")?.call { filters() } ?: getFilterList()

suspend fun CatalogueSource.layerPopular(page: Int) =
    MangaLayerDispatch.current(this, "catalog")?.await { popular(page) } ?: getPopularManga(page)

suspend fun CatalogueSource.layerLatest(page: Int) =
    MangaLayerDispatch.current(this, "catalog")?.await { latest(page) } ?: getLatestUpdates(page)

suspend fun CatalogueSource.layerSearch(page: Int, query: String, filters: FilterList): MangasPage {
    if (page == 1) query.toHttpUrlOrNull()?.let { url ->
        layerByUrl(url)?.let { manga -> return MangasPage(listOf(manga), false) }
    }
    val catalog = MangaLayerDispatch.current(this, "catalog")
    return catalog?.await { search(page, query, filters) }
        ?: getSearchManga(page, query, if (catalog?.incompatible == true) getFilterList() else filters)
}

suspend fun CatalogueSource.layerRelated(manga: SManga): List<SManga> =
    MangaLayerDispatch.current(this, "update")?.await { related(manga) } ?: fetchRelatedMangaList(manga)

suspend fun MangaSource.layerByCatalogId(namespace: String, id: Long): SManga? =
    MangaLayerDispatch.current(this, "identity")?.await { byCatalogId(namespace, id) }
        ?: (this as? MangaCatalogIdResolver)?.findMangaByCatalogId(namespace, id)

suspend fun MangaSource.layerByCatalogLink(url: String): SManga? =
    MangaLayerDispatch.current(this, "identity")?.await { byCatalogLink(url) }
        ?: (this as? MangaCatalogLinkResolver)?.findMangaByCatalogLink(url)

suspend fun MangaSource.layerByUrl(url: HttpUrl): SManga? =
    MangaLayerDispatch.current(this, "identity")?.await { byUrl(url) }

fun HttpSource.layerMangaUrl(manga: SManga): String =
    MangaLayerDispatch.current(this, "identity")?.call { mangaUrl(manga) } ?: getMangaUrl(manga)

fun HttpSource.layerChapterUrl(chapter: SChapter): String =
    MangaLayerDispatch.current(this, "reader")?.call { chapterUrl(chapter) } ?: getChapterUrl(chapter)

/** One immutable reader selection for both pages and images, even when a new layer activates. */
class MangaLayerReading(private val source: HttpSource) {
    private val layer = MangaLayerDispatch.current(source, "reader")
    private var replacesPages = false
    val usesOriginalPages: Boolean get() = !replacesPages

    suspend fun pages(
        chapter: SChapter,
        originalPages: suspend () -> List<Page> = { source.getPageList(chapter) },
    ): List<Page> {
        val pages = layer?.read { pages(chapter) }
        replacesPages = pages != null
        return pages ?: originalPages()
    }

    suspend fun imageUrl(page: Page): String =
        if (replacesPages) requireNotNull(requireNotNull(layer).read {
            imageUrl(page) ?: page.imageUrl ?: throw ReaderLayerContract("Missing page URL")
        })
        else source.getImageUrl(page)

    suspend fun image(page: Page) =
        if (replacesPages) requireNotNull(requireNotNull(layer).read {
            image(page) ?: throw ReaderLayerContract("A layer that supplies pages must also supply their images")
        }) else source.getImage(page)
}

private class ReaderLayerContract(message: String) : IllegalStateException(message)

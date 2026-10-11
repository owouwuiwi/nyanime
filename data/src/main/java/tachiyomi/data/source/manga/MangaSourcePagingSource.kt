package tachiyomi.data.source.manga

import androidx.paging.PagingState
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.items.chapter.model.NoChaptersException
import tachiyomi.domain.search.searchTitle
import tachiyomi.domain.source.manga.repository.SourcePagingSourceType

class SourceSearchPagingSource(
    source: CatalogueSource,
    val query: String,
    val filters: FilterList,
    private val session: tachiyomi.domain.search.SearchSession? = null,
) :
    SourcePagingSource(
        source,
    ) {
    override suspend fun requestNextPage(currentPage: Int): MangasPage {
        val active = session ?: return source.getSearchManga(currentPage, query, filters)
        val page = active.search(
            object : tachiyomi.domain.search.ExtensionSearchAdapter<SManga> {
                override val key = source.id.toString()
                override fun identity(item: SManga) = item.url
                override fun title(item: SManga) = item.searchTitle(source.id)
                override suspend fun fetch(page: Int, query: String): tachiyomi.domain.search.SearchPage<SManga> {
                    val result = source.getSearchManga(page, query, filters)
                    return tachiyomi.domain.search.SearchPage(result.mangas, result.hasNextPage)
                }
            },
            currentPage,
        )
        return MangasPage(page.items, page.hasNextPage)
    }
}

class SourcePopularPagingSource(source: CatalogueSource) : SourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): MangasPage {
        return source.getPopularManga(currentPage)
    }
}

class SourceLatestPagingSource(source: CatalogueSource) : SourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): MangasPage {
        return source.getLatestUpdates(currentPage)
    }
}

abstract class SourcePagingSource(
    protected val source: CatalogueSource,
) : SourcePagingSourceType() {

    abstract suspend fun requestNextPage(currentPage: Int): MangasPage

    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, SManga> {
        val page = params.key ?: 1

        val mangasPage = try {
            withIOContext {
                requestNextPage(page.toInt())
                    .takeIf {
                        it.mangas.isNotEmpty() ||
                            page > 1 ||
                            (this@SourcePagingSource is SourceSearchPagingSource)
                    }
                    ?: throw NoChaptersException()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            return LoadResult.Error(e)
        }

        return LoadResult.Page(
            data = mangasPage.mangas,
            prevKey = null,
            nextKey = if (mangasPage.hasNextPage) page + 1 else null,
        )
    }

    override fun getRefreshKey(state: PagingState<Long, SManga>): Long? {
        return null
    }
}

package tachiyomi.data.source.anime

import androidx.paging.PagingState
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.coroutines.CancellationException
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.items.episode.model.NoEpisodesException
import tachiyomi.domain.search.searchTitle
import tachiyomi.domain.source.anime.repository.AnimeSourcePagingSourceType

class AnimeSourceSearchPagingSource(
    source: AnimeSource,
    val query: String,
    val filters: AnimeFilterList,
    private val session: tachiyomi.domain.search.SearchSession? = null,
) : AnimeSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): AnimesPage {
        val active = session ?: return source.getSearchAnime(currentPage, query, filters)
        val page = active.search(
            object : tachiyomi.domain.search.ExtensionSearchAdapter<SAnime> {
                override val key = source.id.toString()
                override fun identity(item: SAnime) = item.url
                override fun title(item: SAnime) = item.searchTitle(source.id)
                override suspend fun fetch(page: Int, query: String): tachiyomi.domain.search.SearchPage<SAnime> {
                    val result = source.getSearchAnime(page, query, filters)
                    return tachiyomi.domain.search.SearchPage(result.animes, result.hasNextPage)
                }
            },
            currentPage,
        )
        return AnimesPage(page.items, page.hasNextPage)
    }
}

class AnimeSourcePopularPagingSource(source: AnimeSource) : AnimeSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): AnimesPage {
        return source.getPopularAnime(currentPage)
    }
}

class AnimeSourceLatestPagingSource(source: AnimeSource) : AnimeSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): AnimesPage {
        return source.getLatestUpdates(currentPage)
    }
}

abstract class AnimeSourcePagingSource(
    protected val source: AnimeSource,
) : AnimeSourcePagingSourceType() {

    abstract suspend fun requestNextPage(currentPage: Int): AnimesPage

    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, SAnime> {
        val page = params.key ?: 1

        val animesPage = try {
            withIOContext {
                requestNextPage(page.toInt())
                    .takeIf {
                        it.animes.isNotEmpty() ||
                            page > 1 ||
                            (this@AnimeSourcePagingSource is AnimeSourceSearchPagingSource)
                    }
                    ?: throw NoEpisodesException()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            return LoadResult.Error(e)
        }

        return LoadResult.Page(
            data = animesPage.animes,
            prevKey = null,
            nextKey = if (animesPage.hasNextPage) page + 1 else null,
        )
    }

    override fun getRefreshKey(state: PagingState<Long, SAnime>): Long? {
        return null
    }
}

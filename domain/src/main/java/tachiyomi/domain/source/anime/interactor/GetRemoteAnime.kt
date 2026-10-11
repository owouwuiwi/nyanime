package tachiyomi.domain.source.anime.interactor

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import tachiyomi.domain.source.anime.repository.AnimeSourcePagingSourceType
import tachiyomi.domain.source.anime.repository.AnimeSourceRepository

class GetRemoteAnime(
    private val repository: AnimeSourceRepository,
) {

    fun subscribe(
        sourceId: Long,
        query: String,
        filterList: AnimeFilterList,
        session: tachiyomi.domain.search.SearchSession? = null,
    ): AnimeSourcePagingSourceType {
        return when (query) {
            QUERY_POPULAR -> repository.getPopularAnime(sourceId)
            QUERY_LATEST -> repository.getLatestAnime(sourceId)
            else -> repository.searchAnime(sourceId, query, filterList, session)
        }
    }

    companion object {
        const val QUERY_POPULAR = "eu.kanade.domain.source.anime.interactor.POPULAR"
        const val QUERY_LATEST = "eu.kanade.domain.source.anime.interactor.LATEST"
    }
}

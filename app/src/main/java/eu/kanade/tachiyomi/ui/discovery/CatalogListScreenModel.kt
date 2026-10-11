package eu.kanade.tachiyomi.ui.discovery

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.base.BasePreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.domain.discovery.AnimeCatalogRepository
import tachiyomi.domain.discovery.CatalogAnime
import tachiyomi.domain.discovery.CatalogFeed
import tachiyomi.domain.discovery.CatalogRequest
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class CatalogListScreenModel(
    private val feed: CatalogFeed,
    private val repository: AnimeCatalogRepository = Injekt.get(),
    private val base: BasePreferences = Injekt.get(),
) : StateScreenModel<CatalogListScreenModel.State>(State()) {
    private var job: Job? = null
    private var page = 1
    private var provider: String? = null
    private val date = java.time.LocalDate.now().toString()

    init {
        if (feed != CatalogFeed.SEARCH) load(reset = true)
    }

    fun search(query: String) {
        job?.cancel()
        mutableState.update { State(query = query) }
        job = screenModelScope.launch {
            delay(400)
            if (query.isNotBlank()) load(reset = true)
        }
    }

    fun load(reset: Boolean = false) {
        if (!reset && (state.value.loading || !state.value.hasNext)) return
        job?.cancel()
        if (reset) {
            page = 1
            provider = null
        }
        val requested = page
        val query = state.value.query
        val previous = if (reset) emptyList() else state.value.items
        job = screenModelScope.launch {
            repository.observe(
                CatalogRequest(feed, requested, query, date, provider),
                offline = base.downloadedOnly().get(),
            ).collect { result ->
                val items = previous + result.data?.items.orEmpty()
                mutableState.update {
                    it.copy(
                        items = items.distinctBy { item -> item.id to item.airingAt },
                        loading = result.loading,
                        stale = result.stale,
                        error = result.error,
                        hasNext = result.data?.hasNextPage ?: false,
                        notice = result.data?.notice,
                    )
                }
                if (!result.loading && result.error == null && result.data != null) {
                    page = requested + 1
                    provider = result.data?.provider
                }
            }
        }
    }

    data class State(
        val items: List<CatalogAnime> = emptyList(),
        val query: String = "",
        val loading: Boolean = false,
        val stale: Boolean = false,
        val error: String? = null,
        val hasNext: Boolean = true,
        val notice: String? = null,
    )
}

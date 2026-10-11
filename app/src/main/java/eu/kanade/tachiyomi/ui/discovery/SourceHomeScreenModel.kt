package eu.kanade.tachiyomi.ui.discovery

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.discovery.ContinueWatchingRefresher
import eu.kanade.tachiyomi.data.discovery.ExtensionHomeServices
import eu.kanade.tachiyomi.data.discovery.LocalHomeItem
import eu.kanade.tachiyomi.data.discovery.LocalHomeSections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.discovery.SectionState
import tachiyomi.domain.discovery.SourceHomeGroupAccess
import tachiyomi.domain.discovery.SourceHomePage
import tachiyomi.domain.discovery.SourceHomeRequest
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SourceHomeScreenModel(
    homeKey: String,
    private val services: ExtensionHomeServices = Injekt.get(),
    private val locals: LocalHomeSections = Injekt.get(),
    private val continueWatchingRefresher: ContinueWatchingRefresher = Injekt.get(),
) : StateScreenModel<SourceHomeScreenModel.State>(State()) {
    private val accessFlow = services.observeGroup(homeKey)
    private val visible = MutableStateFlow(false)
    private val feeds = SourceHomeFeedLoader(screenModelScope, services.merged) { state.value.access }

    init {
        screenModelScope.launch {
            feeds.sections.collectLatest { sections ->
                mutableState.update { it.copy(sections = sections) }
                state.value.access.group?.let { group ->
                    val privacy = Injekt.get<eu.kanade.domain.source.anime.interactor.GetAnimeIncognitoState>()
                    withContext(Dispatchers.IO) {
                        val items = sections.values.flatMap { it.data?.items.orEmpty() }
                        val privateSources = items.mapTo(mutableSetOf()) { it.source }
                            .filterTo(mutableSetOf()) { privacy.await(it) }
                        currentCoroutineContext().ensureActive()
                        eu.kanade.tachiyomi.ui.search.AtlasExploreCache.offer(
                            items.filterNot { it.source in privateSources }
                                .map { eu.kanade.tachiyomi.ui.search.AtlasEntry.video(it, group.id, group.title) },
                        )
                    }
                }
            }
        }
        screenModelScope.launch {
            accessFlow.collectLatest { access ->
                feeds.reset()
                mutableState.value = State(access = access)
                val source = access.group ?: return@collectLatest
                visible.collectLatest { active ->
                    if (!active) return@collectLatest
                    coroutineScope {
                        if (!access.offline) launch { continueWatchingRefresher.refresh(source.sourceIds) }
                        launch {
                            locals.resume(source.sourceIds).observe().collect { value ->
                                mutableState.update { it.copy(resume = value) }
                            }
                        }
                        launch {
                            locals.updates(source.sourceIds).observe().collect { value ->
                                mutableState.update { it.copy(updates = value) }
                            }
                        }
                    }
                }
            }
        }
    }

    fun load(sectionId: String, refresh: Boolean = false, date: String? = null) {
        if (refresh && !state.value.access.offline) restartArtwork()
        feeds.load(SourceHomeRequest(sectionId, date = date), refresh)
    }

    fun refresh() {
        if (state.value.access.offline) return
        restartArtwork()
        feeds.refresh(force = true)
    }

    private fun restartArtwork() = mutableState.update { it.copy(artworkRefreshKey = it.artworkRefreshKey + 1) }

    fun onResume() {
        visible.value = true
        feeds.resume()
    }

    fun onPause() {
        visible.value = false
        feeds.pause()
    }

    data class State(
        val access: SourceHomeGroupAccess = SourceHomeGroupAccess(loading = true),
        val sections: Map<String, SectionState<SourceHomePage>> = emptyMap(),
        val resume: SectionState<List<LocalHomeItem>> = SectionState(),
        val updates: SectionState<List<LocalHomeItem>> = SectionState(),
        val artworkRefreshKey: Int = 0,
    )
}

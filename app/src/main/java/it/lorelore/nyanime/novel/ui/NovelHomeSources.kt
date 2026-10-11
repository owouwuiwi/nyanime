package it.lorelore.nyanime.novel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.discovery.PosterCard
import eu.kanade.presentation.discovery.SectionHeader
import eu.kanade.tachiyomi.R
import it.lorelore.nyanime.novel.source.NovelHomeRepository
import it.lorelore.nyanime.novel.source.NovelSourceRegistration
import it.lorelore.nyanime.novel.source.NovelSources
import it.lorelore.nyanime.source.novel.NovelEntry
import it.lorelore.nyanime.source.novel.NovelHomeSection
import it.lorelore.nyanime.source.novel.NovelHomeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal data class NovelHomeSourceSection(
    val sourceId: Long,
    val sourceName: String,
    val revision: String,
    val section: NovelHomeSection,
    val entries: List<NovelEntry> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
) {
    val key
        get() = "$sourceId:${section.id}"
}

internal data class NovelHomeSourcesState(
    val sources: List<NovelSourceRegistration> = emptyList(),
    val sections: List<NovelHomeSourceSection> = emptyList(),
)

/**
 * Only the visible Home asks sources for explicitly declared Home sections, never empty searches.
 */
@Composable
internal fun rememberNovelHomeSources(active: Boolean, retry: Int): NovelHomeSourcesState {
    val sources = remember { Injekt.get<NovelSources>() }
    val base = remember { Injekt.get<BasePreferences>() }
    val loader = remember(sources) { NovelHomeRepository(sources::resolve) }
    val privacy = remember { arrayOfNulls<Boolean>(1) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val snapshot by
        produceState(NovelHomeSourcesState(), active, retry, lifecycle) {
            if (!active) return@produceState
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    sources.observe(),
                    base.downloadedOnly().changes(),
                    base.incognitoMode().changes(),
                ) { inventory, localOnly, incognito ->
                    Triple(inventory, localOnly, incognito)
                }
                    .collectLatest { (inventory, localOnly, incognito) ->
                        if (privacy[0] != incognito) {
                            loader.clear()
                            privacy[0] = incognito
                        }
                        if (localOnly) {
                            value = NovelHomeSourcesState()
                            return@collectLatest
                        }
                        val previous = value.sections.associateBy { it.key }
                        val declared =
                            inventory.sources.flatMap { registration ->
                                val home = registration.source as? NovelHomeSource
                                val sections =
                                    try {
                                        home?.homeSections.orEmpty().also { list ->
                                            require(
                                                list.size <= 16 &&
                                                    list.map { it.id }.distinct().size == list.size,
                                            )
                                        }
                                    } catch (cancel: CancellationException) {
                                        throw cancel
                                    } catch (_: Exception) {
                                        emptyList()
                                    }
                                sections.map {
                                    val section =
                                        NovelHomeSourceSection(
                                            registration.source.id,
                                            registration.source.name,
                                            registration.revision,
                                            it,
                                        )
                                    registration to
                                        section.copy(
                                            entries =
                                            previous[section.key]
                                                ?.takeIf { old ->
                                                    old.revision == registration.revision
                                                }
                                                ?.entries
                                                .orEmpty(),
                                        )
                                }
                            }
                        value = NovelHomeSourcesState(inventory.sources, declared.map { it.second })
                        coroutineScope {
                            val permits = Semaphore(3)
                            declared.forEach { (registration, section) ->
                                launch {
                                    val loaded =
                                        try {
                                            permits
                                                .withPermit {
                                                    loader.load(
                                                        registration,
                                                        section.section,
                                                        refresh = retry > 0,
                                                    )
                                                }
                                                .let {
                                                    section.copy(
                                                        entries = it.items,
                                                        loading = false,
                                                    )
                                                }
                                        } catch (_: TimeoutCancellationException) {
                                            section.copy(loading = false, failed = true)
                                        } catch (cancel: CancellationException) {
                                            throw cancel
                                        } catch (_: Exception) {
                                            section.copy(loading = false, failed = true)
                                        }
                                    value =
                                        value.copy(
                                            sections =
                                            value.sections.map {
                                                if (it.key == section.key) loaded else it
                                            },
                                        )
                                }
                            }
                        }
                    }
            }
        }
    return snapshot
}

internal fun LazyListScope.novelHomeSources(
    snapshot: NovelHomeSourcesState,
    onDetails: (Long, String) -> Unit,
    onExplore: (Long) -> Unit,
    retry: () -> Unit,
) {
    snapshot.sections.forEach { section ->
        item("source-home:${section.key}", contentType = "novel-source-home") {
            Column {
                SectionHeader(section.section.title) { onExplore(section.sourceId) }
                if (section.loading) LinearProgressIndicator(Modifier.padding(horizontal = 20.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(
                        section.entries,
                        key = { it.reference },
                        contentType = { "novel-source-title" },
                    ) { entry ->
                        PosterCard(
                            entry.title,
                            entry.cover?.let {
                                NovelSourceCover(section.sourceId, it, entry.reference)
                            } ?: R.drawable.ic_novel_cover,
                            section.sourceName,
                            { onDetails(section.sourceId, entry.reference) },
                        )
                    }
                }
                if (section.failed) {
                    Text(
                        stringResource(R.string.novel_home_partial),
                        Modifier.padding(horizontal = 20.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(retry, Modifier.padding(horizontal = 12.dp)) {
                        Text(stringResource(R.string.novel_retry))
                    }
                } else if (!section.loading && section.entries.isEmpty()) {
                    Text(
                        stringResource(R.string.novel_home_empty_section),
                        Modifier.padding(horizontal = 20.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (snapshot.sources.isNotEmpty()) {
        item("explore-novel-sources") {
            Column {
                Text(
                    stringResource(R.string.novel_home_sources),
                    Modifier.padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(snapshot.sources, key = { it.source.id }) { registration ->
                        AssistChip(
                            { onExplore(registration.source.id) },
                            { Text(registration.source.name) },
                        )
                    }
                }
            }
        }
    }
}

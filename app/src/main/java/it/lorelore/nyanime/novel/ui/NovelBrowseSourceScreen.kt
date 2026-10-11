package it.lorelore.nyanime.novel.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import coil3.compose.AsyncImage
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import it.lorelore.nyanime.novel.source.NovelSearchPager
import it.lorelore.nyanime.novel.source.NovelSources
import it.lorelore.nyanime.source.novel.NovelEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Native per-source search. No discovery query is issued just because this screen opens. */
class NovelBrowseSourceScreen(private val sourceId: Long) : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val sources = remember { Injekt.get<NovelSources>() }
        val scope = rememberCoroutineScope()
        var query by rememberSaveable { mutableStateOf("") }
        var sourceName by remember { mutableStateOf("") }
        var entries by remember { mutableStateOf<List<NovelEntry>>(emptyList()) }
        var busy by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        var more by remember { mutableStateOf(false) }
        var page by remember { mutableStateOf(1) }
        var revision by remember { mutableStateOf("") }
        var pager by remember { mutableStateOf(NovelSearchPager(sources::resolve)) }
        var generation by remember { mutableStateOf(0L) }
        val scroll = rememberLazyGridState()
        LaunchedEffect(sourceId) {
            try {
                sourceName = sources.resolve(sourceId).source.name
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                failed = true
            }
        }
        LaunchedEffect(query) {
            val token = ++generation
            entries = emptyList()
            more = false
            page = 1
            failed = false
            busy = query.isNotBlank()
            pager = NovelSearchPager(sources::resolve)
            if (query.isBlank()) return@LaunchedEffect
            delay(350)
            try {
                val registration = sources.resolve(sourceId)
                revision = registration.revision
                val response = pager.fetch(sourceId, revision, query, 1)
                if (generation == token) {
                    entries = response.items
                    more = response.nextCursor != null
                    page = 2
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (generation == token) failed = true
            } finally {
                if (generation == token) busy = false
            }
        }
        Scaffold(
            topBar = {
                Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp)) {
                    Row {
                        IconButton({ navigator?.pop() }) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                stringResource(R.string.guide_back),
                            )
                        }
                        Text(
                            sourceName,
                            Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    OutlinedTextField(
                        query,
                        { query = it.take(2048) },
                        Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.studio_search_source)) },
                    )
                }
            },
        ) { padding ->
            LazyVerticalGrid(
                GridCells.Adaptive(140.dp),
                Modifier.fillMaxSize().padding(padding).padding(12.dp),
                state = scroll,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (query.isBlank()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            stringResource(R.string.studio_search_source_hint),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(entries, key = { it.reference }) { entry ->
                    Card(
                        Modifier.clickable {
                            navigator?.push(NovelDetailsScreen(sourceId, entry.reference))
                        },
                    ) {
                        Column {
                            AsyncImage(
                                entry.cover?.let {
                                    NovelSourceCover(sourceId, it, entry.reference)
                                },
                                null,
                                Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                                contentScale = ContentScale.Crop,
                            )
                            Text(
                                entry.title,
                                Modifier.padding(12.dp),
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (busy) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        CircularProgressIndicator(Modifier.padding(24.dp))
                    }
                }
                if (failed) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            stringResource(R.string.studio_source_search_failed),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (more && !busy) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        TextButton(
                            {
                                val token = generation
                                val currentPager = pager
                                val currentQuery = query
                                val next = page
                                busy = true
                                scope.launch {
                                    try {
                                        val response =
                                            currentPager.fetch(
                                                sourceId,
                                                revision,
                                                currentQuery,
                                                next,
                                            )
                                        if (generation == token) {
                                            entries =
                                                (entries + response.items).distinctBy {
                                                    it.reference
                                                }
                                            more = response.nextCursor != null
                                            page = next + 1
                                            failed = false
                                        }
                                    } catch (cancel: CancellationException) {
                                        throw cancel
                                    } catch (_: Exception) {
                                        if (generation == token) failed = true
                                    } finally {
                                        if (generation == token) busy = false
                                    }
                                }
                            },
                            Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.studio_search_more))
                        }
                    }
                }
            }
        }
    }
}

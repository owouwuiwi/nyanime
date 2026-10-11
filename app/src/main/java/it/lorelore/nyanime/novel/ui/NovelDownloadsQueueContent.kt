package it.lorelore.nyanime.novel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import eu.kanade.tachiyomi.R
import it.lorelore.nyanime.novel.NovelDownloadSummary
import it.lorelore.nyanime.novel.NovelLibrary
import it.lorelore.nyanime.novel.download.NovelDownloads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
internal fun NovelDownloadsQueueContent(contentPadding: PaddingValues) {
    val downloads = remember { Injekt.get<NovelDownloads>() }
    val library = remember { Injekt.get<NovelLibrary>() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val navigator = LocalNavigator.current
    var limit by remember { mutableStateOf(40) }
    var retry by remember { mutableStateOf(0) }
    var loadFailed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(emptySet<String>()) }
    var failed by remember { mutableStateOf(emptySet<String>()) }
    val rows by
        produceState<List<NovelDownloadSummary>?>(null, lifecycle, downloads, limit, retry) {
            loadFailed = false
            try {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    downloads.store.observeList(limit).collect { value = it }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                loadFailed = true
            }
        }
    fun action(id: String, operation: suspend () -> Unit) {
        if (id in busy) return
        busy += id
        failed -= id
        scope.launch {
            try {
                operation()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                failed += id
            } finally {
                busy -= id
            }
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (loadFailed) {
            item {
                Column(Modifier.padding(24.dp)) {
                    Text(stringResource(R.string.novel_storage_error))
                    TextButton({ retry++ }) { Text(stringResource(R.string.novel_retry)) }
                }
            }
        } else if (rows == null) {
            item {
                androidx.compose.material3.LinearProgressIndicator(
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else if (rows.orEmpty().isEmpty()) {
            item {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.novel_download_empty),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        stringResource(R.string.novel_download_empty_detail),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(rows.orEmpty(), key = { it.progress.bookId }) { row ->
            val id = row.progress.bookId
            Column(
                Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(row.title, style = MaterialTheme.typography.titleLarge)
                val details = {
                    action(id) {
                        library.store.get(id)?.origin?.let {
                            navigator?.push(NovelDetailsScreen(it.sourceId, it.reference))
                        }
                    }
                }
                NovelDownloadPanel(
                    row.progress,
                    id in busy,
                    id in failed,
                    details,
                    onPause = { action(id) { downloads.pause(id) } },
                    onRetry = { action(id) { downloads.retry(id) } },
                    onRemove = { action(id) { downloads.remove(id) } },
                )
            }
        }
        if (rows.orEmpty().size == limit && limit < 200) {
            item {
                TextButton({ limit = minOf(limit + 40, 200) }) {
                    Text(stringResource(R.string.novel_load_more))
                }
            }
        }
    }
}

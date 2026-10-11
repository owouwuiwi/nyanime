package it.lorelore.nyanime.novel.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.discovery.LocalHomeContentInset
import eu.kanade.presentation.discovery.PosterCard
import eu.kanade.presentation.discovery.SectionHeader
import eu.kanade.presentation.privacy.privacyRegion
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.discovery.DiscoveryTab
import eu.kanade.tachiyomi.ui.history.HistoriesTab
import eu.kanade.tachiyomi.ui.library.LibrariesTab
import eu.kanade.tachiyomi.ui.privacy.PrivacyArea
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect

internal object NovelHome {
    const val CATEGORY = "nyanime:novel"
    val refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
}

@Composable
internal fun NovelHomeContent(active: Boolean, onCycleCategory: () -> Unit) {
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(active) {
        if (active) NovelHome.refreshRequests.collect { retry++ }
    }
    val resume =
        rememberNovelShelf(
            "",
            10,
            retry,
            enabled = active,
            kind = NovelShelfKind.RESUME,
            retainWhenDisabled = true,
        )
    val collection = rememberNovelShelf("", 12, retry, enabled = active, retainWhenDisabled = true)
    val sourceHome = rememberNovelHomeSources(active, retry)
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) context.startActivity(NovelReaderActivity.importIntent(context, uri))
        }
    val openLibrary = {
        LibrariesTab.showNovels()
        navigator.push(LibrariesTab)
    }
    val scroll = rememberLazyListState()
    DiscoveryTab.HandleHomeReselect(scroll, onCycleCategory, active)
    LazyColumn(
        Modifier.fillMaxSize().privacyRegion(PrivacyArea.LIBRARY),
        state = scroll,
        contentPadding =
        PaddingValues(
            top = LocalHomeContentInset.current,
            bottom = 24.dp + eu.kanade.tachiyomi.ui.home.LocalFloatingNavigationInset.current,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("actions") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button({ picker.launch(arrayOf("application/epub+zip", "text/plain")) }) {
                    Text(stringResource(R.string.novel_import))
                }
                TextButton(openLibrary) { Text(stringResource(R.string.novel_open_library)) }
            }
        }
        if (resume.entries.isNotEmpty()) {
            item("resume") {
                SectionHeader(stringResource(R.string.novel_home_resume)) {
                    HistoriesTab.showNovels()
                    navigator.push(HistoriesTab)
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(resume.entries, key = { it.id }, contentType = { "novel-resume" }) { entry ->
                        NovelReadingCard(entry, modifier = Modifier.width(300.dp))
                    }
                }
            }
        }
        if (collection.entries.isNotEmpty()) {
            item("collection") {
                SectionHeader(stringResource(R.string.novel_your_books), openLibrary)
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(collection.entries, key = { it.id }, contentType = { "novel-cover" }) { entry ->
                        PosterCard(
                            title = entry.item.book.title,
                            cover = entry.cover,
                            subtitle = entry.item.book.authors.joinToString().ifBlank { null },
                            onClick = {
                                context.startActivity(NovelReaderActivity.intent(context, entry.id))
                            },
                        )
                    }
                }
            }
        } else if (collection.loading) {
            item("loading") { CircularProgressIndicator(Modifier.padding(24.dp)) }
        } else if (!collection.error && sourceHome.sources.isEmpty()) {
            item("empty") {
                Text(
                    stringResource(R.string.novel_empty_detail),
                    Modifier.padding(horizontal = 20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (collection.error || resume.error) {
            item("retry") {
                TextButton({ retry++ }, Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.novel_retry))
                }
            }
        }
        novelHomeSources(
            sourceHome,
            onDetails = { source, reference ->
                navigator.push(NovelDetailsScreen(source, reference))
            },
            onExplore = { navigator.push(NovelBrowseSourceScreen(it)) },
            retry = { retry++ },
        )
    }
}

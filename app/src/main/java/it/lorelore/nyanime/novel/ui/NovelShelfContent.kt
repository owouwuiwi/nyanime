package it.lorelore.nyanime.novel.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import eu.kanade.presentation.privacy.privacyRegion
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.privacy.PrivacyArea
import it.lorelore.nyanime.novel.NovelBook
import it.lorelore.nyanime.novel.NovelLibrary
import it.lorelore.nyanime.presentation.library.LibraryEntryRow
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
internal fun NovelShelfContent() {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val library = remember { Injekt.get<NovelLibrary>() }
    var query by rememberSaveable { mutableStateOf("") }
    var requested by rememberSaveable(query) { mutableIntStateOf(40) }
    var retry by remember { mutableIntStateOf(0) }
    var remove by remember { mutableStateOf<NovelBook?>(null) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                context.startActivity(NovelReaderActivity.importIntent(context, uri))
            }
        }
    val state = rememberNovelShelf(query, requested, retry)
    Column(Modifier.fillMaxSize().privacyRegion(PrivacyArea.LIBRARY)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.novel_library),
                style = MaterialTheme.typography.titleLarge,
            )
            Button(
                { picker.launch(arrayOf("application/epub+zip", "text/plain")) },
                Modifier.testTag("novel_import"),
            ) {
                Icon(Icons.Outlined.Add, null)
                Text(stringResource(R.string.novel_import), Modifier.padding(start = 6.dp))
            }
        }
        OutlinedTextField(
            query,
            { query = it.take(256) },
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            placeholder = { Text(stringResource(R.string.novel_library_search)) },
        )
        val scroll = rememberLazyListState()
        LazyColumn(
            state = scroll,
            modifier = Modifier.weight(1f).testTag("novel_library"),
            contentPadding =
            androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                bottom = 136.dp,
            ),
        ) {
            if (state.loading) item { CircularProgressIndicator(Modifier.padding(24.dp)) }
            if (!state.loading && state.entries.isEmpty() && !state.error) {
                item {
                    Column(
                        Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            stringResource(
                                if (query.isBlank()) {
                                    R.string.novel_empty
                                } else {
                                    R.string.novel_no_books_found
                                },
                            ),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            stringResource(
                                if (query.isBlank()) {
                                    R.string.novel_empty_detail
                                } else {
                                    R.string.novel_no_books_found_detail
                                },
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(state.entries, key = { it.id }, contentType = { "novel-book" }) { item ->
                val fraction = item.fraction
                val open = {
                    context.startActivity(NovelReaderActivity.intent(context, item.item.book.id))
                }
                LibraryEntryRow(
                    title = item.item.book.title,
                    cover = item.cover,
                    progress =
                    fraction?.let {
                        stringResource(R.string.novel_percent_read, (it * 100).toInt())
                    },
                    fraction = fraction?.toFloat(),
                    status =
                    item.item.book.authors.joinToString().ifBlank {
                        item.item.book.format.name
                    },
                    news = false,
                    manga = true,
                    downloads = item.downloads.toLong(),
                    downloading = item.downloading,
                    selected = false,
                    labels = null,
                    onOpen = open,
                    onLongClick = { remove = item.item.book },
                    onContinue = open,
                    onDownload =
                    item.item.book.origin?.let { origin ->
                        {
                            navigator?.push(
                                NovelDetailsScreen(
                                    origin.sourceId,
                                    origin.reference,
                                    selectDownload = true,
                                ),
                            )
                            Unit
                        }
                    },
                    mediumLabel = stringResource(R.string.novel_library),
                    posterTransition = false,
                )
            }
            if (state.more) {
                item {
                    TextButton({ requested += 40 }, Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.novel_load_more))
                    }
                }
            }
            if (state.error) {
                item {
                    TextButton({ retry++ }, Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.novel_retry))
                    }
                }
            }
        }
    }
    remove?.let { book ->
        NovelRemoveDialog(
            book = book,
            onDismiss = { remove = null },
            onRemove = {
                library.commit { library.store.setFavorite(book.id, false) }
                remove = null
            },
        )
    }
}

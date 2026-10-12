package eu.kanade.tachiyomi.ui.reading

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.reading.ReadingTogetherManager
import eu.kanade.tachiyomi.ui.watch.rememberRoomText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.items.chapter.model.Chapter
import tachiyomi.domain.search.LexicalTitleMatcher

/** A personal binding requires two explicit choices; similarity never silently chooses an edition. */
@Composable
fun ReadingCopyDialog(manager: ReadingTogetherManager, onDismiss: () -> Unit) {
    val text = rememberRoomText()
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var titleId by rememberSaveable { mutableStateOf<Long?>(null) }
    var chapterId by rememberSaveable { mutableStateOf<Long?>(null) }
    var titles by remember { mutableStateOf<List<Manga>>(emptyList()) }
    var chapters by remember { mutableStateOf<List<Chapter>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val matcher = remember { LexicalTitleMatcher() }
    val shownTitles = remember(titles, query) {
        if (query.isBlank()) {
            titles
        } else {
            titles.map { it to matcher.score(query, it.title) }
                .filter { it.second >= 70 }.sortedByDescending { it.second }.map { it.first }
        }
    }
    LaunchedEffect(titleId) {
        loading = true
        error = null
        try {
            if (titleId == null) titles = manager.copyTitles() else chapters = manager.copyChapters(titleId!!)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            error = text(R.string.room_reading_source_error)
        } finally {
            loading = false
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text(R.string.room_reading_copy_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text(R.string.room_reading_copy_hint), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text(R.string.room_reading_copies),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    query,
                    { query = it },
                    Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(text(R.string.room_reading_copy_search)) },
                )
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!loading &&
                    error == null &&
                    (if (titleId == null) shownTitles.isEmpty() else chapters.isEmpty())
                ) {
                    Text(text(R.string.room_reading_copy_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(Modifier.heightIn(min = 100.dp, max = 320.dp)) {
                    if (titleId == null) {
                        items(shownTitles, key = { it.id }) { title ->
                            Text(
                                title.title,
                                Modifier.fillMaxWidth().clickable {
                                    titleId = title.id
                                    chapterId = null
                                    query = ""
                                }.padding(vertical = 14.dp),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        items(
                            chapters.filter {
                                it.name.contains(query, ignoreCase = true)
                            },
                            key = { it.id },
                        ) { chapter ->
                            Text(
                                chapter.name,
                                Modifier.fillMaxWidth().clickable { chapterId = chapter.id }.padding(vertical = 14.dp),
                                color = if (chapterId == chapter.id) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (titleId != null) {
                    TextButton(onClick = {
                        titleId = null
                        chapterId = null
                        query = ""
                    }) {
                        Text(text(R.string.room_back))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = chapterId != null && titleId != null && !loading, onClick = {
                val manga = titleId ?: return@TextButton
                val chapter = chapterId ?: return@TextButton
                loading = true
                scope.launch {
                    try {
                        if (manager.bindCopy(manga, chapter)) {
                            onDismiss()
                        } else {
                            error =
                                text(R.string.room_reading_failed)
                        }
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (_: Exception) {
                        error = text(R.string.room_reading_failed)
                    } finally {
                        loading = false
                    }
                }
            }) { Text(text(R.string.room_reading_copy_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(text(R.string.room_cancel)) } },
    )
}

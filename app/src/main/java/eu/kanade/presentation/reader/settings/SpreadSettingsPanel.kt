package eu.kanade.presentation.reader.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.spread.SpreadGrouping
import eu.kanade.tachiyomi.ui.reader.spread.SpreadMode
import eu.kanade.tachiyomi.ui.reader.spread.SpreadStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tachiyomi.presentation.core.components.CheckboxItem

private tailrec fun Context.readerActivity(): ReaderActivity? = when (this) {
    is ReaderActivity -> this
    is ContextWrapper -> baseContext.readerActivity()
    else -> null
}

internal data class SpreadSelection(val enabled: Boolean, val setEnabled: (Boolean) -> Unit)

/** The spread switch is independent of the existing reading-direction modes. */
@Composable
internal fun rememberSpreadSelection(): SpreadSelection {
    val context = LocalContext.current
    val activity = context.readerActivity() ?: return SpreadSelection(false) {}
    val state by activity.viewModel.state.collectAsState()
    val manga = state.manga ?: return SpreadSelection(false) {}
    val store = remember(context) { SpreadStore.get(context) }
    val revision by store.changes.collectAsState()
    val mode = remember(revision, manga.source, manga.url) { store.get(manga.source, manga.url).mode }
    val changes = remember(manga.source, manga.url) { Mutex() }
    val guided = manga.viewerFlags and nyanime.panels.GUIDED_READING_FLAG != 0L
    return SpreadSelection(!guided && mode != SpreadMode.SINGLE) { enabled ->
        // Applying the mode closes the sheet immediately; persist in the reader's
        // lifecycle rather than a coroutine belonging to that disappearing sheet.
        activity.lifecycleScope.launch {
            changes.withLock {
                withContext(Dispatchers.IO) {
                    store.update(manga.source, manga.url) { latest ->
                        latest.copy(
                            mode = when {
                                !enabled -> SpreadMode.SINGLE
                                latest.mode == SpreadMode.SINGLE -> SpreadMode.PAIRS
                                else -> latest.mode
                            },
                        )
                    }
                }
                if (enabled && guided) activity.viewModel.setGuidedReading(false)
            }
        }
    }
}

@Composable
internal fun SpreadSettingsPanel() {
    val context = LocalContext.current
    val activity = context.readerActivity() ?: return
    val state by activity.viewModel.state.collectAsState()
    val manga = state.manga ?: return
    val chapter = state.viewerChapters?.currChapter ?: return
    val store = remember(context) { SpreadStore.get(context) }
    val revision by store.changes.collectAsState()
    val record = remember(revision, manga.source, manga.url) { store.get(manga.source, manga.url) }
    val scope = rememberCoroutineScope()
    val changes = remember(manga.source, manga.url) { Mutex() }
    val count = chapter.pages?.size ?: 0
    val offset = record.offsetFor(chapter.chapter.url).coerceIn(0, count)
    var offsetText by remember(chapter.chapter.url, offset) { mutableStateOf(offset.toString()) }
    val focus = LocalFocusManager.current
    val guided = manga.viewerFlags and nyanime.panels.GUIDED_READING_FLAG != 0L
    if (record.mode == SpreadMode.SINGLE || guided) return
    fun change(
        mode: SpreadMode? = null,
        newOffset: Int? = null,
        delta: Int? = null,
        all: Boolean = false,
        reset: Boolean = false,
    ) {
        scope.launch {
            changes.withLock {
                withContext(Dispatchers.IO) {
                    store.update(manga.source, manga.url) { latest ->
                        when {
                            reset -> latest.copy(chapterOffsets = latest.chapterOffsets - chapter.chapter.url)
                            all -> latest.copy(
                                offset = (newOffset ?: offset).coerceIn(0, count),
                                chapterOffsets = latest.chapterOffsets - chapter.chapter.url,
                            )
                            newOffset != null -> latest.copy(
                                chapterOffsets =
                                latest.chapterOffsets + (chapter.chapter.url to newOffset.coerceIn(0, count)),
                            )
                            delta != null -> latest.copy(
                                chapterOffsets = latest.chapterOffsets +
                                    (
                                        chapter.chapter.url to
                                            (latest.offsetFor(chapter.chapter.url) + delta).coerceIn(0, count)
                                        ),
                            )
                            else -> latest.copy(mode = mode ?: latest.mode)
                        }
                    }
                }
                if (mode != null && guided) activity.viewModel.setGuidedReading(false)
            }
        }
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CheckboxItem(
            label = stringResource(R.string.spread_vertical),
            checked = record.mode == SpreadMode.VERTICAL,
            onClick = {
                change(mode = if (record.mode == SpreadMode.VERTICAL) SpreadMode.PAIRS else SpreadMode.VERTICAL)
            },
        )
        if (record.mode != SpreadMode.SINGLE && !guided) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.large,
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.spread_offset), style = MaterialTheme.typography.titleSmall)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { change(delta = -1) }, enabled = offset > 0) {
                            Icon(
                                Icons.Outlined.Remove,
                                contentDescription = stringResource(R.string.spread_offset_less),
                            )
                        }
                        OutlinedTextField(
                            value = offsetText,
                            onValueChange = { value ->
                                if (value.isEmpty()) {
                                    offsetText = ""
                                } else {
                                    value.toIntOrNull()?.coerceIn(0, count)?.let {
                                        offsetText = it.toString()
                                        if (it != offset) change(newOffset = it)
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f).widthIn(min = 72.dp).onFocusChanged {
                                if (!it.isFocused && offsetText.isEmpty()) {
                                    offsetText = "0"
                                    if (offset != 0) change(newOffset = 0)
                                }
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.titleMedium.copy(textAlign = TextAlign.Center),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                            label = { Text(stringResource(R.string.spread_offset_pages)) },
                        )
                        IconButton(onClick = { change(delta = 1) }, enabled = offset < count) {
                            Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.spread_offset_more))
                        }
                    }
                    val preview = SpreadGrouping.group((1..minOf(count, offset + 6)).toList(), offset)
                        .takeLast(4).joinToString("   ·   ") { it.pages.joinToString("–") }
                    Text(
                        preview,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(stringResource(R.string.spread_offset_hint), style = MaterialTheme.typography.bodySmall)
                    Text(
                        stringResource(
                            if (chapter.chapter.url in
                                record.chapterOffsets
                            ) {
                                R.string.spread_chapter_custom
                            } else {
                                R.string.spread_chapter_inherits
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { change(reset = true) }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.spread_reset))
                        }
                        TextButton(
                            onClick = { change(all = true, newOffset = offsetText.toIntOrNull() ?: 0) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColors(),
                        ) { Text(stringResource(R.string.spread_apply_manga)) }
                    }
                }
            }
        }
    }
}

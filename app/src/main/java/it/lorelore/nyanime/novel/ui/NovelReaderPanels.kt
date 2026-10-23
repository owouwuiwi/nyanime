package it.lorelore.nyanime.novel.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import it.lorelore.nyanime.novel.NovelAlignment
import it.lorelore.nyanime.novel.NovelAnchors
import it.lorelore.nyanime.novel.NovelAnnotation
import it.lorelore.nyanime.novel.NovelAppearance
import it.lorelore.nyanime.novel.NovelChapterGroups
import it.lorelore.nyanime.novel.NovelSearch
import it.lorelore.nyanime.novel.NovelTheme
import it.lorelore.nyanime.reader.ReaderPanel
import it.lorelore.nyanime.reader.ReaderPanelAction
import org.readium.r2.shared.publication.Link
import java.nio.charset.Charset

internal data class NovelTocItem(
    val key: String,
    val title: String,
    val link: Link,
    val depth: Int,
    val group: String? = null,
)

private data class NovelIndexRow(
    val key: String,
    val group: it.lorelore.nyanime.novel.NovelChapterGroup<NovelTocItem>? = null,
    val item: NovelTocItem? = null,
)

@Composable
internal fun NovelIndexPanel(
    items: List<NovelTocItem>,
    current: String?,
    onSelect: (Link) -> Unit,
    onDismiss: () -> Unit,
) {
    val groups = remember(items) { NovelChapterGroups.of(items) { it.group } }
    val grouped = groups.any { it.label != null }
    var expanded by
        rememberSaveable(current, items.firstOrNull()?.key) {
            mutableStateOf(
                listOfNotNull(
                    NovelChapterGroups.initial(groups) {
                        it.link.href.resolve().removeFragment().toString() == current
                    },
                ),
            )
        }
    val rows =
        remember(groups, grouped, expanded) {
            groups.flatMap { group ->
                buildList {
                    if (grouped) add(NovelIndexRow("group:${group.key}", group = group))
                    if (!grouped || group.key in expanded) {
                        group.entries.forEach { add(NovelIndexRow(it.key, item = it)) }
                    }
                }
            }
        }
    val initial =
        remember(rows, current) {
            rows
                .indexOfFirst {
                    it.item?.link?.href?.resolve()?.removeFragment()?.toString() == current
                }
                .coerceAtLeast(0)
        }
    val scroll = rememberLazyListState(initialFirstVisibleItemIndex = initial)
    ReaderPanel(stringResource(R.string.novel_index), onDismiss) {
        LazyColumn(state = scroll, modifier = Modifier.testTag("novel_contents")) {
            items(
                rows,
                key = { it.key },
                contentType = { if (it.group != null) "group" else "chapter" },
            ) { row ->
                val group = row.group
                if (group != null) {
                    NovelChapterGroupHeader(
                        group.label,
                        group.entries.size,
                        group.key in expanded,
                        {
                            expanded =
                                if (group.key in expanded) {
                                    expanded - group.key
                                } else {
                                    expanded + group.key
                                }
                        },
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    return@items
                }
                val item = requireNotNull(row.item)
                val active = item.link.href.resolve().removeFragment().toString() == current
                Surface(
                    onClick = { onSelect(item.link) },
                    modifier =
                    Modifier.fillMaxWidth()
                        .padding(
                            start = 12.dp + 12.dp * item.depth.coerceAtMost(4),
                            end = 12.dp,
                            top = 3.dp,
                            bottom = 3.dp,
                        ),
                    shape = RoundedCornerShape(16.dp),
                    color =
                    if (active) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    border =
                    if (active) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .3f))
                    } else {
                        null
                    },
                ) {
                    Text(
                        item.title,
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
internal fun NovelAppearancePanel(
    appearance: NovelAppearance,
    perBook: Boolean,
    onScope: (Boolean) -> Unit,
    onPreview: (NovelAppearance) -> Unit,
    onApply: (NovelAppearance) -> Unit,
    textEncoding: String?,
    onEncoding: (Charset) -> Unit,
    onDismiss: () -> Unit,
) {
    var chooseEncoding by remember { mutableStateOf(false) }
    ReaderPanel(stringResource(R.string.novel_appearance), onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NovelSwitch(
                stringResource(R.string.novel_per_book),
                stringResource(R.string.novel_per_book_detail),
                perBook,
                onScope,
            )
            NovelAppearanceSlider(
                stringResource(R.string.novel_font_size),
                appearance.fontSize,
                .6f..3f,
                { onPreview(appearance.copy(fontSize = it)) },
                { onApply(appearance.copy(fontSize = it)) },
            )
            NovelAppearanceSlider(
                stringResource(R.string.novel_line_height),
                appearance.lineHeight,
                .8f..3f,
                { onPreview(appearance.copy(lineHeight = it)) },
                { onApply(appearance.copy(lineHeight = it)) },
            )
            NovelAppearanceSlider(
                stringResource(R.string.novel_margins),
                appearance.margins,
                0f..3f,
                { onPreview(appearance.copy(margins = it)) },
                { onApply(appearance.copy(margins = it)) },
            )
            Text(stringResource(R.string.novel_font), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "serif" to R.string.novel_serif,
                    "sans-serif" to R.string.novel_sans,
                    "monospace" to R.string.novel_mono,
                )
                    .forEach { (font, label) ->
                        FilterChip(
                            appearance.fontFamily == font,
                            { onApply(appearance.copy(fontFamily = font)) },
                            label = { Text(stringResource(label)) },
                        )
                    }
            }
            Text(stringResource(R.string.novel_theme), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NovelTheme.entries.forEach { theme ->
                    val name =
                        when (theme) {
                            NovelTheme.DEVICE -> R.string.novel_theme_app
                            NovelTheme.LIGHT -> R.string.novel_theme_light
                            NovelTheme.DARK -> R.string.novel_theme_dark
                            NovelTheme.PAPER -> R.string.novel_theme_paper
                        }
                    FilterChip(
                        appearance.theme == theme,
                        { onApply(appearance.copy(theme = theme)) },
                        label = { Text(stringResource(name)) },
                    )
                }
            }
            NovelSwitch(stringResource(R.string.novel_scroll), null, appearance.scroll) {
                onApply(appearance.copy(scroll = it))
            }
            NovelSwitch(
                stringResource(R.string.novel_justify),
                null,
                appearance.alignment == NovelAlignment.JUSTIFY,
            ) {
                onApply(
                    appearance.copy(
                        alignment = if (it) NovelAlignment.JUSTIFY else NovelAlignment.START,
                    ),
                )
            }
            NovelSwitch(
                stringResource(R.string.novel_publisher_styles),
                stringResource(R.string.novel_publisher_styles_detail),
                appearance.publisherStyles,
            ) {
                onApply(appearance.copy(publisherStyles = it))
            }
            NovelSwitch(
                stringResource(R.string.novel_brightness_auto),
                null,
                appearance.brightness == null,
            ) {
                onApply(appearance.copy(brightness = if (it) null else .5f))
            }
            if (textEncoding != null) {
                ReaderPanelAction(
                    title = stringResource(R.string.novel_encoding),
                    detail = textEncoding,
                    onClick = { chooseEncoding = true },
                )
            }
            val brightness = appearance.brightness
            if (brightness != null) {
                NovelAppearanceSlider(
                    stringResource(R.string.novel_brightness),
                    brightness.toDouble(),
                    .03f..1f,
                    { onPreview(appearance.copy(brightness = it.toFloat())) },
                    { onApply(appearance.copy(brightness = it.toFloat())) },
                )
            }
        }
    }
    if (chooseEncoding) {
        AlertDialog(
            onDismissRequest = { chooseEncoding = false },
            title = { Text(stringResource(R.string.novel_encoding)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.novel_encoding_detail))
                    listOf(
                        "UTF-8",
                        "UTF-16LE",
                        "UTF-16BE",
                        "UTF-32LE",
                        "UTF-32BE",
                        "windows-1252",
                        "ISO-8859-1",
                        "Shift_JIS",
                        "GB18030",
                    )
                        .filter { Charset.isSupported(it) }
                        .forEach { name ->
                            TextButton(
                                onClick = {
                                    chooseEncoding = false
                                    onEncoding(Charset.forName(name))
                                },
                                enabled = name != textEncoding,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(name)
                            }
                        }
                }
            },
            confirmButton = {
                TextButton({ chooseEncoding = false }) {
                    Text(stringResource(R.string.novel_cancel))
                }
            },
        )
    }
}

@Composable
private fun NovelAppearanceSlider(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Double) -> Unit,
    onFinished: (Double) -> Unit,
) {
    var latest by remember(value) { mutableStateOf(value) }
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(
                "${(value * 100).toInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value.toFloat().coerceIn(range),
            {
                latest = it.toDouble()
                onChange(latest)
            },
            onValueChangeFinished = { onFinished(latest) },
            valueRange = range,
        )
    }
}

@Composable
private fun NovelSwitch(
    title: String,
    detail: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked, onChange)
    }
}

@Composable
internal fun NovelSearchPanel(
    query: String,
    progress: NovelSearch.Progress?,
    hits: List<NovelSearch.Hit>,
    onQuery: (String) -> Unit,
    onSelect: (NovelSearch.Hit) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    cachedOnly: Boolean = false,
) {
    var selected by remember { mutableStateOf<NovelSearch.Hit?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    ReaderPanel(
        stringResource(R.string.novel_search),
        {
            selected?.let(onSelect)
            onDismiss()
        },
        inputAware = true,
        dismissRequested = selected != null,
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (cachedOnly) {
                Text(
                    stringResource(R.string.novel_search_cached_only),
                    Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { onQuery(it.take(256)) },
                singleLine = true,
                modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .testTag("novel_search_input"),
                placeholder = { Text(stringResource(R.string.novel_search_hint)) },
                shape = RoundedCornerShape(18.dp),
            )
            if (progress != null && progress.active && progress.completed < progress.total) {
                LinearProgressIndicator(
                    progress = { progress.completed.toFloat() / progress.total.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                )
                Text(
                    stringResource(
                        if (progress.phase == NovelSearch.Phase.PREPARING) {
                            R.string.novel_search_preparing
                        } else {
                            R.string.novel_search_progress
                        },
                        progress.completed,
                        progress.total,
                    ),
                    Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (progress?.limited == true) {
                Text(
                    stringResource(R.string.novel_search_limit),
                    Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (progress?.failed?.let { it > 0 } == true && progress.limited != true) {
                Text(
                    stringResource(R.string.novel_search_partial),
                    Modifier.padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onRetry, Modifier.padding(horizontal = 12.dp)) {
                    Text(stringResource(R.string.novel_retry))
                }
            }
            LazyColumn(Modifier.weight(1f, fill = false).testTag("novel_search_results")) {
                if (hits.isEmpty()) {
                    item {
                        NovelEmptyPanel(
                            stringResource(
                                if (query.isBlank()) {
                                    R.string.novel_search_prompt
                                } else {
                                    R.string.novel_search_empty
                                },
                            ),
                        )
                    }
                }
                items(hits, key = { "${it.href}:${it.characterOffset}" }) { hit ->
                    ReaderPanelAction(
                        hit.chapter,
                        hit.excerpt,
                        onClick = {
                            focus.clearFocus()
                            keyboard?.hide()
                            selected = hit
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun NovelNotesPanel(
    annotations: List<NovelAnnotation>,
    version: String,
    onSelect: (NovelAnnotation) -> Unit,
    onDelete: (NovelAnnotation) -> Unit,
    onDismiss: () -> Unit,
    anchorState: (NovelAnnotation) -> NovelAnchors.State = {
        if (it.contentVersion == version) NovelAnchors.State.CURRENT else NovelAnchors.State.CHANGED
    },
) {
    ReaderPanel(stringResource(R.string.novel_notes), onDismiss) {
        LazyColumn(Modifier.testTag("novel_notes")) {
            if (annotations.isEmpty()) {
                item {
                    NovelEmptyPanel(
                        stringResource(R.string.novel_notes_empty),
                        stringResource(R.string.novel_notes_empty_detail),
                    )
                }
            }
            items(annotations, key = { it.id }) { note ->
                Surface(
                    onClick = { onSelect(note) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Row(
                        Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text(
                                note.note.ifBlank {
                                    note.quote.ifBlank { stringResource(R.string.novel_bookmark) }
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val state = anchorState(note)
                            if (state != NovelAnchors.State.CURRENT) {
                                Text(
                                    stringResource(
                                        if (state == NovelAnchors.State.UNVERIFIED) {
                                            R.string.novel_note_unverified
                                        } else {
                                            R.string.novel_note_changed
                                        },
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        IconButton({ onDelete(note) }) {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                stringResource(R.string.novel_delete),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NovelEmptyPanel(title: String, detail: String? = null) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (detail != null) {
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun NovelNoteEditor(
    annotation: NovelAnnotation,
    onSave: (NovelAnnotation) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable(annotation.id) { mutableStateOf(annotation.note) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.novel_note)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (annotation.quote.isNotBlank()) {
                    Text(
                        annotation.quote,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                OutlinedTextField(
                    text,
                    { text = it.take(65537) },
                    minLines = 3,
                    maxLines = 7,
                    label = { Text(stringResource(R.string.novel_note_hint)) },
                    isError = text.length > 65536,
                    supportingText = {
                        if (text.length > 65536) Text(stringResource(R.string.novel_note_limit))
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.length <= 65536,
                onClick = {
                    onSave(annotation.copy(note = text, modifiedAt = System.currentTimeMillis()))
                },
            ) {
                Text(stringResource(R.string.novel_save))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.novel_cancel)) } },
    )
}

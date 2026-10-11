package it.lorelore.nyanime.reader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.InputAdaptiveSheet
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.modernMotionEnabled
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import kotlinx.coroutines.launch

/** A sheet is a sibling of the renderer; changing it never recreates the page. */
@Composable
internal fun ReaderPanel(
    title: String,
    onDismiss: () -> Unit,
    inputAware: Boolean = false,
    dismissRequested: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints {
        val wide = maxWidth >= 840.dp
        var closeRequested by remember { mutableStateOf(false) }
        val panel: @Composable () -> Unit = {
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = maxHeight * .88f)
                    .semantics { testTagsAsResourceId = true }
                    .testTag("reader_panel"),
            ) {
                Row(
                    Modifier.fillMaxWidth()
                        .padding(start = 22.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title,
                        Modifier.weight(1f).semantics { heading() },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    IconButton({ closeRequested = true }) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.reader_close))
                    }
                }
                content()
            }
        }
        if (!wide) {
            if (inputAware) {
                InputAdaptiveSheet(
                    onDismissRequest = onDismiss,
                    dismissRequested = closeRequested || dismissRequested,
                    content = panel,
                )
            } else {
                AdaptiveSheet(
                    onDismissRequest = onDismiss,
                    dismissRequested = closeRequested || dismissRequested,
                    content = panel,
                )
            }
        } else {
            ReaderSidePanel(onDismiss, closeRequested || dismissRequested, inputAware, panel)
        }
    }
}

@Composable
private fun ReaderSidePanel(
    onDismiss: () -> Unit,
    closeRequested: Boolean,
    inputAware: Boolean,
    content: @Composable () -> Unit,
) {
    val motion = modernMotionEnabled()
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val dismiss: () -> Unit = {
        if (!closing) {
            closing = true
            scope.launch {
                progress.animateTo(0f, tween(if (motion) ModernMotion.EXIT_MILLIS else 0))
                onDismiss()
            }
        }
    }
    LaunchedEffect(closeRequested) {
        if (closeRequested) dismiss()
    }
    Dialog(
        dismiss,
        properties =
        DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier.fillMaxSize()
                .clickable(interactionSource = null, indication = null, onClick = dismiss),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Surface(
                Modifier.systemBarsPadding()
                    .padding(12.dp)
                    .width(400.dp)
                    .fillMaxHeight()
                    .then(if (inputAware) Modifier.imePadding() else Modifier)
                    .graphicsLayer {
                        alpha = progress.value
                        translationX = (1f - progress.value) * 24.dp.toPx()
                    }
                    .clickable(interactionSource = null, indication = null, onClick = {}),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                content()
            }
        }
        BackHandler(onBack = dismiss)
        LaunchedEffect(Unit) {
            if (!closing) {
                progress.animateTo(
                    1f,
                    tween(
                        if (motion) ModernMotion.PAGE_MILLIS else 0,
                        easing = ModernMotion.SmoothOutEasing,
                    ),
                )
            }
        }
    }
}

@Composable
internal fun ReaderChaptersPanel(
    chapters: List<ReaderViewModel.ChapterMenuItem>,
    currentId: Long?,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val position =
        remember(chapters, currentId) {
            chapters.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = position)
    ReaderPanel(stringResource(R.string.reader_chapters), onDismiss) {
        LazyColumn(state = list, modifier = Modifier.testTag("reader_chapter_list")) {
            items(chapters, key = { it.id }, contentType = { "chapter" }) { chapter ->
                val current = chapter.id == currentId
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                    onClick = { onSelect(chapter.id) },
                    shape = RoundedCornerShape(16.dp),
                    color =
                    if (current) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    border =
                    if (current) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .35f))
                    } else {
                        null
                    },
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                chapter.title,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val detail =
                                if (current) {
                                    stringResource(R.string.reader_current_chapter)
                                } else {
                                    chapter.scanlator
                                }
                            if (!detail.isNullOrBlank()) {
                                Text(
                                    detail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (chapter.bookmarked) {
                            Icon(
                                Icons.Outlined.Bookmark,
                                stringResource(R.string.reader_bookmark),
                                Modifier.size(18.dp),
                            )
                        }
                        if (chapter.read) {
                            Icon(
                                Icons.Outlined.Check,
                                stringResource(R.string.reader_read),
                                Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ReaderActionsPanel(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ReaderPanel(title, onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

@Composable
internal fun ReaderPanelAction(
    title: String,
    detail: String? = null,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth()
            .testTag("reader_action_$title")
            .semantics(mergeDescendants = true) {}
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color =
        if (active) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (active) {
                Icon(Icons.Outlined.Check, null, Modifier.padding(start = 12.dp).size(20.dp))
            }
        }
    }
}

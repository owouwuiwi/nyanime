package eu.kanade.presentation.reader.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.roundToInt

/** A small side rail; dragging previews a page number and performs one jump on release. */
@Composable
internal fun VerticalChapterNavigator(
    currentPage: Int,
    totalPages: Int,
    enabledPrevious: Boolean,
    enabledNext: Boolean,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onPageIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pages = totalPages.coerceAtLeast(1)
    var preview by remember(pages) { mutableIntStateOf(currentPage.coerceIn(1, pages)) }
    var dragging by remember { mutableStateOf(false) }
    val onJump by rememberUpdatedState(onPageIndexChange)
    val latestPage by rememberUpdatedState(currentPage)
    LaunchedEffect(currentPage, pages) {
        if (!dragging) preview = currentPage.coerceIn(1, pages)
    }
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    val accent = MaterialTheme.colorScheme.primary
    val label = stringResource(MR.strings.reader_page_navigator)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp).copy(alpha = 0.95f))
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FilledIconButton(onClick = onPreviousChapter, enabled = enabledPrevious, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Outlined.SkipPrevious,
                contentDescription = stringResource(MR.strings.action_previous_chapter),
                modifier = Modifier.rotate(90f),
            )
        }
        Text(
            text = if (totalPages > 0) preview.toString() else "—",
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
        )
        if (pages > 1) {
            Canvas(
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .semantics {
                        contentDescription = label
                        progressBarRangeInfo = ProgressBarRangeInfo(preview.toFloat(), 1f..pages.toFloat(), pages - 2)
                        setProgress {
                            if (!it.isFinite()) {
                                false
                            } else {
                                preview = it.roundToInt().coerceIn(1, pages)
                                onJump(preview - 1)
                                true
                            }
                        }
                    }
                    .pointerInput(pages) {
                        detectTapGestures { position ->
                            val edge = 10.dp.toPx()
                            preview = navigatorPageAt(position.y - edge, size.height - edge * 2, pages)
                            onJump(preview - 1)
                        }
                    }
                    .pointerInput(pages) {
                        detectDragGestures(
                            onDragStart = { position ->
                                dragging = true
                                val edge = 10.dp.toPx()
                                preview = navigatorPageAt(position.y - edge, size.height - edge * 2, pages)
                            },
                            onDragEnd = {
                                dragging = false
                                onJump(preview - 1)
                            },
                            onDragCancel = {
                                dragging = false
                                preview = latestPage.coerceIn(1, pages)
                            },
                        ) { change, _ ->
                            change.consume()
                            val edge = 10.dp.toPx()
                            preview = navigatorPageAt(change.position.y - edge, size.height - edge * 2, pages)
                        }
                    },
            ) {
                val top = 10.dp.toPx().coerceAtMost(size.height / 2)
                val bottom = (size.height - top).coerceAtLeast(top)
                val y = top + (bottom - top) * (preview - 1).toFloat() / (pages - 1)
                val x = size.width / 2
                drawLine(track, Offset(x, top), Offset(x, bottom), 4.dp.toPx(), StrokeCap.Round)
                drawLine(accent, Offset(x, top), Offset(x, y), 4.dp.toPx(), StrokeCap.Round)
                drawCircle(accent, 7.dp.toPx(), Offset(x, y))
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        Text(
            text = if (totalPages > 0) pages.toString() else "—",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        FilledIconButton(onClick = onNextChapter, enabled = enabledNext, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Outlined.SkipNext,
                contentDescription = stringResource(MR.strings.action_next_chapter),
                modifier = Modifier.rotate(90f),
            )
        }
    }
}

internal fun navigatorPageAt(y: Float, height: Float, totalPages: Int): Int {
    if (totalPages <= 1 || height <= 0 || !height.isFinite() || !y.isFinite()) return 1
    return (1 + (y / height).coerceIn(0f, 1f) * (totalPages - 1)).roundToInt().coerceIn(1, totalPages)
}

package it.lorelore.nyanime.novel.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.NoteAlt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.modernMotionEnabled
import eu.kanade.tachiyomi.R

internal enum class NovelPanel {
    INDEX,
    APPEARANCE,
    SEARCH,
    NOTES,
}

/**
 * Floating controls are siblings of the EPUB fragment; opening one does not replace its renderer.
 */
@Composable
internal fun NovelReaderChrome(
    title: String,
    chapter: String,
    progress: Double?,
    visible: Boolean,
    bookmarked: Boolean,
    onBack: () -> Unit,
    onBookmark: () -> Unit,
    onSeek: (Double) -> Unit,
    onPanel: (NovelPanel) -> Unit,
) {
    val motion = modernMotionEnabled()
    val enter = fadeIn(tween(if (motion) ModernMotion.PAGE_MILLIS else 0))
    val exit = fadeOut(tween(if (motion) ModernMotion.EXIT_MILLIS else 0))
    val surface = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .97f)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible,
            Modifier.align(Alignment.TopCenter),
            enter = enter,
            exit = exit,
        ) {
            Surface(
                Modifier.statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = surface,
                border = border,
            ) {
                Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            stringResource(R.string.novel_back),
                        )
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 8.dp)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (chapter.isNotBlank()) {
                            Text(
                                chapter,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    IconButton(onBookmark, Modifier.testTag("novel_bookmark")) {
                        Icon(
                            if (bookmarked) {
                                Icons.Filled.Bookmark
                            } else {
                                Icons.Outlined.BookmarkBorder
                            },
                            stringResource(
                                if (bookmarked) {
                                    R.string.novel_remove_bookmark
                                } else {
                                    R.string.novel_bookmark
                                },
                            ),
                            tint =
                            if (bookmarked) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }
        AnimatedVisibility(
            visible,
            Modifier.align(Alignment.BottomCenter),
            enter = enter,
            exit = exit,
        ) {
            Surface(
                Modifier.navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = surface,
                border = border,
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    var seeking by remember { mutableStateOf<Float?>(null) }
                    val current = progress?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)?.toFloat()
                    val interaction = remember { MutableInteractionSource() }
                    val dragged by interaction.collectIsDraggedAsState()
                    val pressed by interaction.collectIsPressedAsState()
                    val diameter by
                        animateFloatAsState(
                            if (dragged || pressed) 18f else 12f,
                            ModernMotion.controlResponse(motion),
                            label = "novel_seek_thumb",
                        )
                    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                    val active = MaterialTheme.colorScheme.primary
                    val inactive = MaterialTheme.colorScheme.onSurface.copy(alpha = .16f)
                    LaunchedEffect(current) { if (!dragged) seeking = null }
                    LaunchedEffect(seeking, dragged, pressed) {
                        if (seeking != null && !dragged && !pressed) {
                            kotlinx.coroutines.delay(1500)
                            seeking = null
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Slider(
                            value = seeking ?: current ?: 0f,
                            onValueChange = { seeking = it },
                            onValueChangeFinished = {
                                seeking?.let { onSeek(it.toDouble()) }
                            },
                            enabled = current != null,
                            modifier = Modifier.weight(1f).testTag("novel_progress"),
                            interactionSource = interaction,
                            thumb = {
                                Surface(
                                    Modifier.size(diameter.dp),
                                    shape = CircleShape,
                                    color = if (current == null) inactive else active,
                                ) {}
                            },
                            track = { state ->
                                Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                                    val start = if (rtl) size.width else 0f
                                    val end = if (rtl) 0f else size.width
                                    val y = size.height / 2
                                    drawLine(
                                        inactive,
                                        Offset(start, y),
                                        Offset(end, y),
                                        size.height,
                                        StrokeCap.Round,
                                    )
                                    drawLine(
                                        if (current == null) inactive else active,
                                        Offset(start, y),
                                        Offset(
                                            start + (end - start) * state.value.coerceIn(0f, 1f),
                                            y,
                                        ),
                                        size.height,
                                        StrokeCap.Round,
                                    )
                                }
                            },
                        )
                        Text(
                            if (current == null) {
                                "—"
                            } else {
                                "${((seeking ?: current) * 100).toInt()}%"
                            },
                            Modifier.padding(start = 8.dp, end = 4.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val columns =
                            if (maxWidth < 288.dp * LocalDensity.current.fontScale) 2 else 4
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            NovelPanel.entries.chunked(columns).forEach { panels ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    panels.forEach { panel ->
                                        val label =
                                            stringResource(
                                                when (panel) {
                                                    NovelPanel.INDEX -> R.string.novel_index
                                                    NovelPanel.APPEARANCE ->
                                                        R.string.novel_appearance
                                                    NovelPanel.SEARCH -> R.string.novel_search
                                                    NovelPanel.NOTES -> R.string.novel_notes
                                                },
                                            )
                                        val icon =
                                            when (panel) {
                                                NovelPanel.INDEX -> Icons.AutoMirrored.Outlined.List
                                                NovelPanel.APPEARANCE -> Icons.Outlined.FormatSize
                                                NovelPanel.SEARCH -> Icons.Outlined.Search
                                                NovelPanel.NOTES -> Icons.Outlined.NoteAlt
                                            }
                                        NovelDockAction(
                                            label,
                                            icon,
                                            Modifier.weight(1f),
                                            inline = columns == 2,
                                        ) {
                                            onPanel(panel)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NovelDockAction(
    label: String,
    image: ImageVector,
    modifier: Modifier,
    inline: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = androidx.compose.ui.graphics.Color.Transparent,
    ) {
        if (inline) {
            Row(
                Modifier.heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(image, null, Modifier.size(23.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Column(
                Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(image, null, Modifier.size(23.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

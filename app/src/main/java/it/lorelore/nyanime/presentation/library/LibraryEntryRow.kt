package it.lorelore.nyanime.presentation.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.discovery.SourceHomeArtwork
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.presentation.motion.posterForeground
import eu.kanade.presentation.motion.posterOpen
import eu.kanade.presentation.motion.posterSource
import eu.kanade.presentation.motion.rememberPosterSource
import eu.kanade.presentation.privacy.nsfwPrivacy
import eu.kanade.tachiyomi.R
import it.lorelore.nyanime.presentation.components.QuietActionIconButton
import tachiyomi.domain.entries.EntryCover

/** The same row is used by All, Anime and Manga. No provider policy or image-derived palette. */
@Composable
internal fun LibraryEntryRow(
    title: String,
    cover: EntryCover,
    progress: String?,
    fraction: Float?,
    status: String,
    news: Boolean,
    manga: Boolean,
    downloads: Long,
    downloading: Boolean,
    selected: Boolean,
    labels: List<String>?,
    onOpen: () -> Unit,
    onLongClick: () -> Unit,
    onContinue: (() -> Unit)?,
    onDownload: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val poster = rememberPosterSource(cover)
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(18.dp)
    val motion = appMotionEnabled()
    val container by animateColorAsState(
        when {
            selected -> MaterialTheme.colorScheme.secondaryContainer
            news -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = .35f)
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
        tween(if (motion) ModernMotion.RESIZE_MILLIS else 0),
        label = "library-entry-surface",
    )
    val completion by animateFloatAsState(
        fraction?.coerceIn(0f, 1f) ?: 0f,
        tween(if (motion) ModernMotion.RESIZE_MILLIS else 0),
        label = "library-entry-progress",
    )
    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp).nsfwPrivacy(cover, labels)
            .combinedClickable(onClick = posterOpen(poster, title, onOpen), onLongClick = onLongClick),
        shape = shape,
        color = container,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected || news) {
                accent
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f)
            },
        ),
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 140.dp)) {
            Box(Modifier.width(78.dp).fillMaxHeight().posterSource(poster)) {
                SourceHomeArtwork(
                    cover,
                    Modifier.fillMaxSize(),
                    initialPainter = poster.painter,
                    onPainterReady = { poster.painter = it },
                )
            }
            Column(
                Modifier.weight(1f).posterForeground(poster).padding(
                    start = 14.dp,
                    end = 8.dp,
                    top = 14.dp,
                    bottom = 14.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    stringResource(if (manga) R.string.library_medium_manga else R.string.library_medium_anime),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (news) {
                        accent
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                progress?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (downloads > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Download,
                            null,
                            Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(R.string.library_downloaded_count, downloads),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                fraction?.let {
                    Box(
                        Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    ) {
                        Box(Modifier.fillMaxWidth(completion).fillMaxHeight().background(accent))
                    }
                }
            }
            Column(
                Modifier.padding(end = 6.dp, top = 8.dp, bottom = 8.dp).align(Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                onContinue?.let {
                    QuietActionIconButton(
                        icon = if (manga) Icons.Outlined.AutoStories else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(
                            if (manga) {
                                R.string.library_shelf_continue_reading
                            } else {
                                R.string.library_shelf_continue_watching
                            },
                        ),
                        onClick = it,
                        modifier = Modifier.size(48.dp),
                    )
                }
                if (onDownload != null) {
                    IconButton(onClick = onDownload, enabled = !downloading) {
                        if (downloading) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Outlined.Download,
                                stringResource(R.string.library_shelf_download_all),
                                Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

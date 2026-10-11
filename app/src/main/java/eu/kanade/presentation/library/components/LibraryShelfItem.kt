package eu.kanade.presentation.library.components

import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.get
import coil3.Image
import coil3.asDrawable
import eu.kanade.presentation.entries.components.ItemCover
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.presentation.motion.posterForeground
import eu.kanade.presentation.motion.posterOpen
import eu.kanade.presentation.motion.posterSource
import eu.kanade.presentation.motion.posterSourcePlaceholder
import eu.kanade.presentation.motion.rememberPosterSource
import eu.kanade.presentation.privacy.nsfwPrivacy
import eu.kanade.presentation.theme.LocalNyanimeStyle
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.presentation.util.formatEpisodeNumber
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.library.LibraryShelfStatus
import eu.kanade.tachiyomi.util.lang.toRelativeString
import eu.kanade.tachiyomi.util.system.getBitmapOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.entries.EntryCover
import java.time.Instant
import java.time.ZoneId
import android.graphics.Color as AndroidColor

private val NewReleaseColor = Color(0xFFFF6B72)
private val ShelfItemHeight = 92.dp
private val ShelfCoverWidth = ShelfItemHeight * ItemCover.Book.ratio
private val accentCache = LruCache<String, Int>(192)

internal enum class LibraryShelfPosition {
    Only,
    First,
    Middle,
    Last,
}

@Composable
internal fun LibraryShelfItem(
    title: String,
    coverData: EntryCover,
    status: LibraryShelfStatus,
    progressText: String?,
    unviewedCount: Long,
    downloadCount: Long,
    isAnime: Boolean,
    isLocal: Boolean,
    selected: Boolean,
    now: Long,
    downloading: Boolean,
    position: LibraryShelfPosition,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onContinue: (() -> Unit)?,
    onDownload: (() -> Unit)?,
    contentLabels: List<String>?,
    modifier: Modifier = Modifier,
    progressFraction: Float? = null,
) {
    if (LocalNyanimeStyle.current) {
        it.lorelore.nyanime.presentation.library.LibraryEntryRow(
            title = title, cover = coverData, progress = progressText, fraction = progressFraction,
            status = shelfStatusText(status, unviewedCount, isAnime, now), news = status.newReleaseCount > 0,
            manga = !isAnime, downloads = downloadCount, downloading = downloading, selected = selected,
            labels = contentLabels, onOpen = onClick, onLongClick = onLongClick,
            onContinue = onContinue,
            onDownload = onDownload.takeUnless {
                isLocal || unviewedCount <= 0
            },
            modifier = modifier,
        )
        return
    }
    val motion = appMotionEnabled()
    val poster = if (LocalNyanimeStyle.current) rememberPosterSource(coverData) else null
    val openDetails = if (poster != null) posterOpen(poster, title, onClick) else onClick
    var painter by remember(coverData) { mutableStateOf<Painter?>(null) }
    var image by remember(coverData) { mutableStateOf<Image?>(null) }
    val extracted = rememberShelfAccent(coverData.toString(), image)
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val accent = extracted ?: MaterialTheme.colorScheme.primary
    val target = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        status.newReleaseCount > 0 -> lerp(base, NewReleaseColor, .28f)
        else -> lerp(base, accent, .18f)
    }
    val container by animateColorAsState(
        target,
        animationSpec = if (motion) tween(ModernMotion.RESIZE_MILLIS) else snap(),
        label = "library-shelf-color",
    )
    val border = when {
        selected -> MaterialTheme.colorScheme.secondary
        status.newReleaseCount > 0 -> NewReleaseColor
        else -> accent.copy(alpha = .62f)
    }
    val statusText = shelfStatusText(status, unviewedCount, isAnime, now)
    val shape = shelfShape(position)
    val coverShape = shelfCoverShape(position)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(ShelfItemHeight)
            .nsfwPrivacy(coverData, contentLabels)
            .combinedClickable(onClick = openDetails, onLongClick = onLongClick),
        shape = shape,
        color = container,
        border = BorderStroke(if (selected) 2.dp else 1.dp, border),
        tonalElevation = if (selected) 4.dp else 1.dp,
    ) {
        Row(
            Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(ShelfCoverWidth)
                    .fillMaxHeight()
                    .posterSource(poster),
            ) {
                ItemCover.Book(
                    data = coverData,
                    modifier = Modifier.fillMaxHeight().alpha(if (selected) .78f else 1f),
                    shape = coverShape,
                    initialPainter = posterSourcePlaceholder(poster),
                    onPainterReady = {
                        painter = it
                        poster?.painter = it
                    },
                    onImageReady = { image = it },
                )
                Box(
                    Modifier.fillMaxHeight().width(7.dp).align(Alignment.CenterEnd),
                ) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxHeight().fillMaxWidth()) {
                        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, border.copy(alpha = .8f))))
                    }
                }
            }
            Column(
                Modifier.weight(1f).posterForeground(poster).padding(horizontal = 11.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    progressText?.let {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = accent.copy(alpha = .16f),
                            contentColor = accent,
                        ) {
                            Text(
                                text = it,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                        }
                    }
                    Text(
                        text = statusText,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (status.newReleaseCount > 0) {
                            NewReleaseColor
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (downloadCount > 0) {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = null,
                            modifier = Modifier.padding(start = 6.dp).size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .76f),
                        )
                        Text(
                            text = downloadCount.toString(),
                            modifier = Modifier.padding(start = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .82f),
                        )
                    }
                }
            }
            Row(
                Modifier.posterForeground(poster).padding(end = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onContinue != null || (!isLocal && onDownload != null && unviewedCount > 0)) {
                    VerticalDivider(
                        modifier = Modifier.height(38.dp).padding(horizontal = 2.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f),
                    )
                }
                if (onContinue != null) {
                    ShelfIconButton(
                        icon = if (isAnime) Icons.Filled.PlayArrow else Icons.Outlined.AutoStories,
                        description = stringResource(
                            if (isAnime) {
                                R.string.library_shelf_continue_watching
                            } else {
                                R.string.library_shelf_continue_reading
                            },
                        ),
                        accent = accent,
                        onClick = onContinue,
                    )
                }
                if (!isLocal && onDownload != null && unviewedCount > 0) {
                    ShelfIconButton(
                        icon = Icons.Filled.Download,
                        description = stringResource(R.string.library_shelf_download_all),
                        accent = accent,
                        loading = downloading,
                        onClick = onDownload,
                    )
                }
            }
        }
    }
}

@Composable
internal fun LibraryShelfSectionHeader(
    title: String,
    count: Int,
    isNews: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 13.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
            color = if (isNews) NewReleaseColor else MaterialTheme.colorScheme.onSurface,
        )
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = if (isNews) {
                NewReleaseColor.copy(alpha = .16f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            contentColor = if (isNews) NewReleaseColor else MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Text(
                text = count.toString(),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = if (isNews) {
                NewReleaseColor.copy(alpha = .28f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)
            },
        )
    }
}

@Composable
private fun shelfStatusText(status: LibraryShelfStatus, unviewedCount: Long, anime: Boolean, now: Long): String {
    if (status.newReleaseCount > 0) {
        val number = status.newestReleaseNumber
        if (status.newReleaseCount == 1 && number != null) {
            return stringResource(
                if (anime) R.string.library_shelf_new_episode else R.string.library_shelf_new_chapter,
                if (anime) formatEpisodeNumber(number) else formatChapterNumber(number),
            )
        }
        return pluralStringResource(
            if (anime) R.plurals.library_shelf_new_episodes else R.plurals.library_shelf_new_chapters,
            status.newReleaseCount,
            status.newReleaseCount,
        )
    }
    val at = status.nextReleaseAt
    val number = status.nextReleaseNumber
    if (at != null && at > now && number != null) {
        val relative = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDateTime()
            .toRelativeString(LocalContext.current)
        return stringResource(
            if (anime) R.string.library_shelf_next_episode else R.string.library_shelf_next_chapter,
            if (anime) formatEpisodeNumber(number) else formatChapterNumber(number),
            relative,
        )
    }
    return if (unviewedCount > 0) {
        pluralStringResource(
            if (anime) R.plurals.library_shelf_to_watch else R.plurals.library_shelf_to_read,
            unviewedCount.toInt(),
            unviewedCount.toInt(),
        )
    } else {
        stringResource(R.string.library_shelf_caught_up)
    }
}

@Composable
private fun ShelfIconButton(
    icon: ImageVector,
    description: String,
    accent: Color,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = !loading,
        modifier = Modifier.size(40.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = accent)
        } else {
            Icon(icon, description, Modifier.size(21.dp), tint = accent)
        }
    }
}

private fun shelfShape(position: LibraryShelfPosition): Shape = when (position) {
    LibraryShelfPosition.Only -> RoundedCornerShape(18.dp)
    LibraryShelfPosition.First -> RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = 4.dp,
        bottomEnd = 4.dp,
    )
    LibraryShelfPosition.Middle -> RoundedCornerShape(6.dp)
    LibraryShelfPosition.Last -> RoundedCornerShape(
        topStart = 4.dp,
        topEnd = 4.dp,
        bottomStart = 18.dp,
        bottomEnd = 18.dp,
    )
}

private fun shelfCoverShape(position: LibraryShelfPosition): Shape = when (position) {
    LibraryShelfPosition.Only -> RoundedCornerShape(
        topStart = 17.dp,
        bottomStart = 17.dp,
        topEnd = 3.dp,
        bottomEnd = 3.dp,
    )
    LibraryShelfPosition.First -> RoundedCornerShape(topStart = 17.dp, topEnd = 3.dp, bottomEnd = 3.dp)
    LibraryShelfPosition.Middle -> RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp)
    LibraryShelfPosition.Last -> RoundedCornerShape(bottomStart = 17.dp, topEnd = 3.dp, bottomEnd = 3.dp)
}

@Composable
private fun rememberShelfAccent(key: String, image: Image?): Color? {
    var value by remember(key) { mutableStateOf(accentCache.get(key)?.let(::Color)) }
    val context = LocalContext.current
    LaunchedEffect(key, image) {
        if (value != null) return@LaunchedEffect
        val bitmap = runCatching {
            image?.asDrawable(context.resources)?.getBitmapOrNull()
        }.getOrNull() ?: return@LaunchedEffect
        val color = withContext(Dispatchers.Default) {
            runCatching {
                val readable = if (bitmap.config == android.graphics.Bitmap.Config.HARDWARE) {
                    bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                } else {
                    bitmap
                } ?: return@runCatching null
                try {
                    extractAccent(readable)
                } finally {
                    if (readable !== bitmap) readable.recycle()
                }
            }.getOrNull()
        } ?: return@LaunchedEffect
        accentCache.put(key, color)
        value = Color(color)
    }
    return value
}

private fun extractAccent(bitmap: android.graphics.Bitmap): Int? {
    val bins = Array(12) { FloatArray(4) }
    val stepX = (bitmap.width / 10).coerceAtLeast(1)
    val stepY = (bitmap.height / 10).coerceAtLeast(1)
    for (y in stepY / 2 until bitmap.height step stepY) {
        for (x in stepX / 2 until bitmap.width step stepX) {
            val argb = bitmap[x, y]
            if (AndroidColor.alpha(argb) < 160) continue
            val hsv = FloatArray(3)
            AndroidColor.colorToHSV(argb, hsv)
            if (hsv[2] < .10f || hsv[2] > .94f || hsv[1] < .12f) continue
            val weight = hsv[1] * (.55f + hsv[2] * .45f)
            val bin = ((hsv[0] / 30f).toInt()).coerceIn(0, bins.lastIndex)
            bins[bin][0] += hsv[0] * weight
            bins[bin][1] += hsv[1] * weight
            bins[bin][2] += hsv[2] * weight
            bins[bin][3] += weight
        }
    }
    val best = bins.maxByOrNull { it[3] }?.takeIf { it[3] > 0f } ?: return null
    val weight = best[3]
    val hsv = floatArrayOf(
        best[0] / weight,
        (best[1] / weight).coerceIn(.35f, .76f),
        (best[2] / weight).coerceIn(.42f, .76f),
    )
    return AndroidColor.HSVToColor(hsv)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryShelfActionsSheet(
    title: String,
    coverData: EntryCover,
    isAnime: Boolean,
    hasUnviewed: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onContinue: (() -> Unit)?,
    onMarkViewed: () -> Unit,
    onMarkUnviewed: () -> Unit,
    onUpdate: () -> Unit,
    onChangeCategory: (() -> Unit)?,
    onSelect: (() -> Unit)?,
    onRemove: (() -> Unit)?,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var acting by remember { mutableStateOf(false) }
    fun act(block: () -> Unit) {
        if (acting) return
        acting = true
        scope.launch {
            sheet.hide()
            onDismiss()
            block()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ItemCover.Book(coverData, Modifier.width(50.dp), shape = RoundedCornerShape(10.dp))
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column {
                    ShelfActionRow(
                        stringResource(R.string.library_shelf_open),
                        Icons.AutoMirrored.Outlined.OpenInNew,
                    ) { act(onOpen) }
                    if (onContinue != null) {
                        ShelfDivider()
                        ShelfActionRow(
                            stringResource(
                                if (isAnime) {
                                    R.string.library_shelf_continue_watching
                                } else {
                                    R.string.library_shelf_continue_reading
                                },
                            ),
                            if (isAnime) Icons.Outlined.PlayCircleOutline else Icons.Outlined.AutoStories,
                        ) { act(onContinue) }
                    }
                    ShelfDivider()
                    ShelfActionRow(
                        stringResource(
                            if (isAnime) {
                                if (hasUnviewed) {
                                    R.string.library_shelf_mark_seen
                                } else {
                                    R.string.library_shelf_mark_unseen
                                }
                            } else {
                                if (hasUnviewed) {
                                    R.string.library_shelf_mark_read
                                } else {
                                    R.string.library_shelf_mark_unread
                                }
                            },
                        ),
                        if (hasUnviewed) Icons.Outlined.CheckCircleOutline else Icons.Outlined.VisibilityOff,
                    ) { act(if (hasUnviewed) onMarkViewed else onMarkUnviewed) }
                    ShelfDivider()
                    ShelfActionRow(
                        stringResource(R.string.library_shelf_update),
                        Icons.Outlined.Refresh,
                    ) { act(onUpdate) }
                    if (onChangeCategory != null) {
                        ShelfDivider()
                        ShelfActionRow(
                            stringResource(R.string.library_shelf_change_category),
                            Icons.Outlined.Category,
                        ) { act(onChangeCategory) }
                    }
                    if (onSelect != null) {
                        ShelfDivider()
                        ShelfActionRow(
                            stringResource(R.string.library_shelf_select),
                            Icons.Outlined.SelectAll,
                        ) { act(onSelect) }
                    }
                }
            }
            if (onRemove != null) {
                Surface(
                    modifier = Modifier.padding(top = 12.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = .55f),
                ) {
                    ShelfActionRow(
                        stringResource(R.string.library_shelf_remove),
                        Icons.Outlined.DeleteOutline,
                        MaterialTheme.colorScheme.error,
                    ) { act(onRemove) }
                }
            }
        }
    }
}

@Composable
private fun ShelfActionRow(
    title: String,
    icon: ImageVector,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, color = Color.Transparent) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(24.dp), tint = tint)
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = tint)
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = tint.copy(alpha = .72f))
        }
    }
}

@Composable
private fun ShelfDivider() {
    HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

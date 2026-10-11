package eu.kanade.presentation.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.entries.components.ItemCover
import eu.kanade.presentation.motion.PosterSource
import eu.kanade.presentation.motion.posterForeground
import eu.kanade.presentation.motion.posterOpen
import eu.kanade.presentation.motion.posterSource
import eu.kanade.presentation.motion.posterSourcePlaceholder
import eu.kanade.presentation.motion.rememberPosterSource
import eu.kanade.presentation.privacy.nsfwPrivacy
import eu.kanade.presentation.theme.LocalNyanimeStyle
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.BadgeGroup
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.selectedBackground
import tachiyomi.domain.entries.EntryCover as EntryCoverModel

object CommonEntryItemDefaults {
    val GridHorizontalSpacer = 4.dp
    val GridVerticalSpacer = 4.dp

    @Suppress("ConstPropertyName")
    const val BrowseFavoriteCoverAlpha = 0.34f
}

private val ContinueViewingButtonSizeSmall = 28.dp
private val ContinueViewingButtonSizeLarge = 32.dp

private val ContinueViewingButtonIconSizeSmall = 16.dp
private val ContinueViewingButtonIconSizeLarge = 20.dp

private val ContinueViewingButtonGridPadding = 6.dp
private val ContinueViewingButtonListSpacing = 8.dp

private const val GRID_SELECTED_COVER_ALPHA = 0.76f

/**
 * Layout of grid list item with title overlaying the cover.
 * Accepts null [title] for a cover-only view.
 */
@Composable
fun EntryCompactGridItem(
    coverData: EntryCoverModel,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    isSelected: Boolean = false,
    title: String? = null,
    onClickContinueViewing: (() -> Unit)? = null,
    coverAlpha: Float = 1f,
    coverBadgeStart: @Composable (RowScope.() -> Unit)? = null,
    coverBadgeEnd: @Composable (RowScope.() -> Unit)? = null,
    contentLabels: List<String>? = null,
    libraryStyle: Boolean = false,
) {
    val modernLibrary = libraryStyle && LocalNyanimeStyle.current
    val poster = rememberEntryPosterSource(coverData)
    val openDetails = if (poster != null) posterOpen(poster, title.orEmpty(), onClick) else onClick

    GridItemSelectable(
        modifier = Modifier.nsfwPrivacy(coverData, contentLabels),
        isSelected = isSelected,
        onClick = openDetails,
        onLongClick = onLongClick,
        modernLibrary = modernLibrary,
    ) {
        EntryGridCover(
            poster = poster,
            cover = {
                ItemCover.Book(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (isSelected) GRID_SELECTED_COVER_ALPHA else coverAlpha)
                        .posterSource(poster),
                    data = coverData,
                    shape = if (modernLibrary) RoundedCornerShape(13.dp) else MaterialTheme.shapes.extraSmall,
                    initialPainter = posterSourcePlaceholder(poster),
                    onPainterReady = { poster?.painter = it },
                )
            },
            badgesStart = coverBadgeStart,
            badgesEnd = coverBadgeEnd,
            content = {
                if (title != null) {
                    CoverTextOverlay(
                        title = title,
                        onClickContinueViewing = onClickContinueViewing,
                    )
                } else if (onClickContinueViewing != null) {
                    ContinueViewingButton(
                        size = ContinueViewingButtonSizeLarge,
                        iconSize = ContinueViewingButtonIconSizeLarge,
                        onClick = onClickContinueViewing,
                        modifier = Modifier
                            .padding(ContinueViewingButtonGridPadding)
                            .align(Alignment.BottomEnd),
                    )
                }
            },
        )
    }
}

/**
 * Title overlay for [EntryCompactGridItem]
 */
@Composable
private fun BoxScope.CoverTextOverlay(
    title: String,
    onClickContinueViewing: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp))
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.3f to Color(0xAA000000),
                    1f to Color(0xAA000000),
                ),
            )
            .fillMaxWidth()
            .align(Alignment.BottomCenter)
            .padding(top = 24.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        GridItemTitle(
            modifier = Modifier
                .weight(1f)
                .padding(8.dp),
            title = title,
            style = MaterialTheme.typography.titleSmall.copy(
                color = Color.White,
                shadow = Shadow(
                    color = Color.Black,
                    blurRadius = 4f,
                ),
            ),
            minLines = 1,
            maxLines = gridTitleMaxLines(),
        )
        if (onClickContinueViewing != null) {
            ContinueViewingButton(
                size = ContinueViewingButtonSizeSmall,
                iconSize = ContinueViewingButtonIconSizeSmall,
                onClick = onClickContinueViewing,
                modifier = Modifier.padding(
                    end = ContinueViewingButtonGridPadding,
                    bottom = ContinueViewingButtonGridPadding,
                ),
            )
        }
    }
}

/**
 * Layout of grid list item with title below the cover.
 */
@Composable
fun EntryComfortableGridItem(
    isSelected: Boolean = false,
    title: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    titleMaxLines: Int = gridTitleMaxLines(),
    coverData: EntryCoverModel,
    coverAlpha: Float = 1f,
    coverBadgeStart: (@Composable RowScope.() -> Unit)? = null,
    coverBadgeEnd: (@Composable RowScope.() -> Unit)? = null,
    onClickContinueViewing: (() -> Unit)? = null,
    contentLabels: List<String>? = null,
    libraryStyle: Boolean = false,
) {
    val modernLibrary = libraryStyle && LocalNyanimeStyle.current
    val poster = rememberEntryPosterSource(coverData)
    val openDetails = if (poster != null) posterOpen(poster, title.orEmpty(), onClick) else onClick

    GridItemSelectable(
        modifier = Modifier.nsfwPrivacy(coverData, contentLabels),
        isSelected = isSelected,
        onClick = openDetails,
        onLongClick = onLongClick,
        modernLibrary = modernLibrary,
    ) {
        Column {
            EntryGridCover(
                poster = poster,
                cover = {
                    ItemCover.Book(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (isSelected) GRID_SELECTED_COVER_ALPHA else coverAlpha)
                            .posterSource(poster),
                        data = coverData,
                        shape = if (modernLibrary) RoundedCornerShape(13.dp) else MaterialTheme.shapes.extraSmall,
                        initialPainter = posterSourcePlaceholder(poster),
                        onPainterReady = { poster?.painter = it },
                    )
                },
                badgesStart = coverBadgeStart,
                badgesEnd = coverBadgeEnd,
                content = {
                    if (onClickContinueViewing != null) {
                        ContinueViewingButton(
                            size = ContinueViewingButtonSizeLarge,
                            iconSize = ContinueViewingButtonIconSizeLarge,
                            onClick = onClickContinueViewing,
                            modifier = Modifier
                                .padding(ContinueViewingButtonGridPadding)
                                .align(Alignment.BottomEnd),
                        )
                    }
                },
            )
            GridItemTitle(
                modifier = Modifier.posterForeground(poster).padding(4.dp),
                title = title,
                style = MaterialTheme.typography.titleSmall,
                minLines = 2,
                maxLines = titleMaxLines,
            )
        }
    }
}

/**
 * Common cover layout to add contents to be drawn on top of the cover.
 */
@Composable
private fun EntryGridCover(
    modifier: Modifier = Modifier,
    poster: PosterSource? = null,
    cover: @Composable BoxScope.() -> Unit = {},
    badgesStart: (@Composable RowScope.() -> Unit)? = null,
    badgesEnd: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable (BoxScope.() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(ItemCover.Book.ratio),
    ) {
        cover()
        Box(Modifier.matchParentSize().posterForeground(poster)) {
            content?.invoke(this)
            if (badgesStart != null) {
                BadgeGroup(
                    modifier = Modifier
                        .padding(4.dp)
                        .align(Alignment.TopStart),
                    content = badgesStart,
                )
            }

            if (badgesEnd != null) {
                BadgeGroup(
                    modifier = Modifier
                        .padding(4.dp)
                        .align(Alignment.TopEnd),
                    content = badgesEnd,
                )
            }
        }
    }
}

@Composable
private fun gridTitleMaxLines() = if (LocalDensity.current.fontScale >= 1.3f) 3 else 2

@Composable
private fun GridItemTitle(
    title: String,
    style: TextStyle,
    minLines: Int,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
) {
    Text(
        modifier = modifier,
        text = title,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        minLines = minLines,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = style,
    )
}

/**
 * Wrapper for grid items to handle selection state, click and long click.
 */
@Composable
private fun GridItemSelectable(
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    modernLibrary: Boolean = false,
    content: @Composable () -> Unit,
) {
    val shape = if (modernLibrary) RoundedCornerShape(16.dp) else MaterialTheme.shapes.small
    val container = if (modernLibrary) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent
    Box(
        modifier = modifier
            .clip(shape)
            .background(container)
            .then(
                if (modernLibrary) {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f), shape)
                } else {
                    Modifier
                },
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .selectedOutline(
                isSelected = isSelected,
                color = MaterialTheme.colorScheme.secondary,
                radius = if (modernLibrary) 16.dp else 0.dp,
            )
            .padding(if (modernLibrary) 3.dp else 4.dp),
    ) {
        val contentColor = if (isSelected) {
            MaterialTheme.colorScheme.onSecondary
        } else {
            LocalContentColor.current
        }
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}

/**
 * @see GridItemSelectable
 */
private fun Modifier.selectedOutline(
    isSelected: Boolean,
    color: Color,
    radius: Dp,
) = drawBehind {
    if (isSelected) {
        drawRoundRect(color = color, cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius.toPx()))
    }
}

/**
 * Layout of list item.
 */
@Composable
fun EntryListItem(
    isSelected: Boolean = false,
    title: String,
    coverData: EntryCoverModel,
    coverAlpha: Float = 1f,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
    badge: @Composable (RowScope.() -> Unit),
    onClickContinueViewing: (() -> Unit)? = null,
    entries: Int = 0,
    containerHeight: Int = 0,
    modifier: Modifier = Modifier,
    contentLabels: List<String>? = null,
    libraryStyle: Boolean = false,
) {
    val poster = rememberEntryPosterSource(coverData)
    val openDetails = if (poster != null) posterOpen(poster, title.orEmpty(), onClick) else onClick
    val modernLibrary = libraryStyle && LocalNyanimeStyle.current
    val shape = RoundedCornerShape(16.dp)

    Row(
        modifier = modifier
            .then(if (modernLibrary) Modifier.padding(horizontal = 12.dp, vertical = 4.dp) else Modifier)
            .nsfwPrivacy(coverData, contentLabels)
            .then(
                if (modernLibrary) {
                    Modifier
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .42f), shape)
                } else {
                    Modifier
                },
            )
            .selectedBackground(isSelected)
            .height(
                when (entries) {
                    0 -> 76.dp
                    else -> {
                        val density = LocalDensity.current
                        with(density) { (containerHeight / entries).toDp() } - (3 / entries).dp
                    }
                },
            )
            .combinedClickable(
                onClick = openDetails,
                onLongClick = onLongClick,
            )
            .padding(horizontal = if (modernLibrary) 8.dp else 16.dp, vertical = if (modernLibrary) 6.dp else 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ItemCover.Book(
            modifier = Modifier
                .fillMaxHeight()
                .alpha(coverAlpha).posterSource(poster),
            data = coverData,
            shape = if (modernLibrary) RoundedCornerShape(11.dp) else MaterialTheme.shapes.extraSmall,
            initialPainter = posterSourcePlaceholder(poster),
            onPainterReady = { poster?.painter = it },
        )
        Text(
            text = title,
            modifier = Modifier
                .posterForeground(poster)
                .padding(horizontal = 16.dp)
                .weight(1f),
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
        BadgeGroup(modifier = Modifier.posterForeground(poster), content = badge)
        if (onClickContinueViewing != null) {
            ContinueViewingButton(
                size = ContinueViewingButtonSizeSmall,
                iconSize = ContinueViewingButtonIconSizeSmall,
                onClick = onClickContinueViewing,
                modifier = Modifier.posterForeground(poster).padding(start = ContinueViewingButtonListSpacing),
            )
        }
    }
}

@Composable
private fun ContinueViewingButton(
    size: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        FilledIconButton(
            onClick = onClick,
            shape = MaterialTheme.shapes.small,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                contentColor = contentColorFor(MaterialTheme.colorScheme.primaryContainer),
            ),
            modifier = Modifier.size(size),
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = stringResource(MR.strings.action_resume),
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
private fun rememberEntryPosterSource(cover: EntryCoverModel): PosterSource? =
    if (LocalNyanimeStyle.current) rememberPosterSource(cover) else null

package eu.kanade.presentation.reader.appbars

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.modernMotionEnabled
import eu.kanade.presentation.reader.components.ChapterNavigator
import eu.kanade.presentation.reader.components.VerticalChapterNavigator
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.spread.SpreadViewer
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LPagerViewer
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import androidx.compose.ui.res.stringResource as androidStringResource

private val animationSpec = tween<IntOffset>(200)

@Composable
fun ReaderAppBars(
    visible: Boolean,
    fullscreen: Boolean,

    mangaTitle: String?,
    chapterTitle: String?,
    navigateUp: () -> Unit,
    onClickTopAppBar: () -> Unit,
    bookmarked: Boolean,
    onToggleBookmarked: () -> Unit,
    onOpenInWebView: (() -> Unit)?,
    onOpenInBrowser: (() -> Unit)?,
    onShare: (() -> Unit)?,

    viewer: Viewer?,
    onNextChapter: () -> Unit,
    enabledNext: Boolean,
    onPreviousChapter: () -> Unit,
    enabledPrevious: Boolean,
    currentPage: Int,
    totalPages: Int,
    onPageIndexChange: (Int) -> Unit,

    readingMode: ReadingMode,
    onClickReadingMode: () -> Unit,
    orientation: ReaderOrientation,
    onClickOrientation: () -> Unit,
    cropEnabled: Boolean,
    onClickCropBorder: () -> Unit,
    onClickSettings: () -> Unit,
    onReadingTogether: () -> Unit = {},
    guidedEnabled: Boolean = false,
    onClickGuided: () -> Unit = {},
    verticalNavigator: Boolean = false,
    musicControls: @Composable () -> Unit = {},
) {
    val motion = modernMotionEnabled()
    val isRtl = viewer is R2LPagerViewer || (viewer as? SpreadViewer)?.isRightToLeft == true
    val backgroundColor = MaterialTheme.colorScheme
        .surfaceColorAtElevation(3.dp)
        .copy(alpha = if (isSystemInDarkTheme()) 0.9f else 0.95f)

    val modifierWithInsetsPadding = if (fullscreen) {
        Modifier.systemBarsPadding()
    } else {
        Modifier
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val vertical = verticalNavigator && maxHeight >= 360.dp
        val navigatorHeight = (maxHeight - 160.dp).coerceAtMost(360.dp)
        Column(
            modifier = Modifier.fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = if (motion) {
                    slideInVertically(
                        initialOffsetY = { -it },
                        animationSpec = animationSpec,
                    )
                } else {
                    EnterTransition.None
                },
                exit = if (motion) {
                    slideOutVertically(
                        targetOffsetY = { -it },
                        animationSpec = animationSpec,
                    )
                } else {
                    ExitTransition.None
                },
            ) {
                Column {
                    AppBar(
                        modifier = modifierWithInsetsPadding
                            .clickable(onClick = onClickTopAppBar),
                        backgroundColor = backgroundColor,
                        title = mangaTitle,
                        subtitle = chapterTitle,
                        navigateUp = navigateUp,
                        actions = {
                            AppBarActions(
                                actions = persistentListOf<AppBar.AppBarAction>().builder()
                                    .apply {
                                        add(
                                            AppBar.Action(
                                                title = androidStringResource(R.string.room_read),
                                                icon = Icons.Outlined.Group,
                                                onClick = onReadingTogether,
                                            ),
                                        )
                                        add(
                                            AppBar.Action(
                                                title = stringResource(
                                                    if (bookmarked) {
                                                        MR.strings.action_remove_bookmark
                                                    } else {
                                                        MR.strings.action_bookmark
                                                    },
                                                ),
                                                icon = if (bookmarked) {
                                                    Icons.Outlined.Bookmark
                                                } else {
                                                    Icons.Outlined.BookmarkBorder
                                                },
                                                onClick = onToggleBookmarked,
                                            ),
                                        )
                                        onOpenInWebView?.let {
                                            add(
                                                AppBar.OverflowAction(
                                                    title = stringResource(MR.strings.action_open_in_web_view),
                                                    onClick = it,
                                                ),
                                            )
                                        }
                                        onOpenInBrowser?.let {
                                            add(
                                                AppBar.OverflowAction(
                                                    title = stringResource(MR.strings.action_open_in_browser),
                                                    onClick = it,
                                                ),
                                            )
                                        }
                                        onShare?.let {
                                            add(
                                                AppBar.OverflowAction(
                                                    title = stringResource(MR.strings.action_share),
                                                    onClick = it,
                                                ),
                                            )
                                        }
                                    }
                                    .build(),
                            )
                        },
                    )
                    musicControls()
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            AnimatedVisibility(
                visible = visible,
                enter = if (motion) {
                    slideInVertically(
                        initialOffsetY = { it },
                        animationSpec = animationSpec,
                    )
                } else {
                    EnterTransition.None
                },
                exit = if (motion) {
                    slideOutVertically(
                        targetOffsetY = { it },
                        animationSpec = animationSpec,
                    )
                } else {
                    ExitTransition.None
                },
            ) {
                Column(
                    modifier = modifierWithInsetsPadding,
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                ) {
                    if (!vertical) {
                        ChapterNavigator(
                            isRtl = isRtl,
                            onNextChapter = onNextChapter,
                            enabledNext = enabledNext,
                            onPreviousChapter = onPreviousChapter,
                            enabledPrevious = enabledPrevious,
                            currentPage = currentPage,
                            totalPages = totalPages,
                            onPageIndexChange = onPageIndexChange,
                        )
                    }
                    BottomReaderBar(
                        backgroundColor = backgroundColor,
                        readingMode = readingMode,
                        onClickReadingMode = onClickReadingMode,
                        orientation = orientation,
                        onClickOrientation = onClickOrientation,
                        cropEnabled = cropEnabled,
                        onClickCropBorder = onClickCropBorder,
                        onClickSettings = onClickSettings,
                        guidedEnabled = guidedEnabled,
                        onClickGuided = onClickGuided,
                        spreadEnabled = viewer is SpreadViewer,
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = visible && vertical,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
            enter = if (motion) ModernMotion.enter() else EnterTransition.None,
            exit = if (motion) ModernMotion.exit() else ExitTransition.None,
        ) {
            key(chapterTitle) {
                VerticalChapterNavigator(
                    currentPage = currentPage,
                    totalPages = totalPages,
                    enabledPrevious = enabledPrevious,
                    enabledNext = enabledNext,
                    onPreviousChapter = onPreviousChapter,
                    onNextChapter = onNextChapter,
                    onPageIndexChange = onPageIndexChange,
                    modifier = Modifier.width(68.dp).height(navigatorHeight),
                )
            }
        }
    }
}

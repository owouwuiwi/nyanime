package eu.kanade.presentation.reader.appbars

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.modernMotionEnabled
import eu.kanade.presentation.reader.components.ChapterNavigator
import eu.kanade.presentation.reader.components.VerticalChapterNavigator
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.spread.SpreadViewer
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LPagerViewer

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
    viewer: Viewer?,
    onNextChapter: () -> Unit,
    enabledNext: Boolean,
    onPreviousChapter: () -> Unit,
    enabledPrevious: Boolean,
    currentPage: Int,
    totalPages: Int,
    onPageIndexChange: (Int) -> Unit,
    onChapters: () -> Unit,
    onMode: () -> Unit,
    onQuality: () -> Unit,
    onMore: () -> Unit,
    verticalNavigator: Boolean = false,
    qualityComparison: Boolean = false,
    onChromeSize: (top: Dp, bottom: Dp) -> Unit = { _, _ -> },
) {
    val motion = modernMotionEnabled()
    val density = LocalDensity.current
    var topHeight by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(68.dp) }
    var bottomHeight by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(140.dp) }
    val isRtl = viewer is R2LPagerViewer || (viewer as? SpreadViewer)?.isRightToLeft == true
    val insets = if (fullscreen) WindowInsets.safeDrawing else WindowInsets.displayCutout
    BoxWithConstraints(
        Modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
    ) {
        val vertical = verticalNavigator && maxHeight >= 360.dp
        val navigatorHeight = (maxHeight - 200.dp).coerceIn(120.dp, 360.dp)
        val enter = if (motion) ModernMotion.enter(220) else EnterTransition.None
        val exit = if (motion) ModernMotion.exit() else ExitTransition.None

        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = enter +
                if (motion) {
                    slideInVertically(tween(220, easing = ModernMotion.SmoothOutEasing)) { -it / 5 }
                } else {
                    EnterTransition.None
                },
            exit = exit,
        ) {
            Box(
                Modifier.fillMaxWidth()
                    .windowInsetsPadding(insets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                ReaderChromeSurface(
                    Modifier.widthIn(max = 680.dp).onSizeChanged {
                        topHeight = with(density) { it.height.toDp() } + 16.dp
                        onChromeSize(topHeight, bottomHeight)
                    },
                ) {
                    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = navigateUp) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.reader_back))
                        }
                        Column(
                            Modifier.weight(1f).clickable(onClick = onClickTopAppBar).padding(vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                mangaTitle.orEmpty(),
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                chapterTitle.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onToggleBookmarked, Modifier.testTag("reader_bookmark_button")) {
                            Icon(
                                if (bookmarked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                                stringResource(
                                    if (bookmarked) R.string.reader_unbookmark else R.string.reader_bookmark,
                                ),
                                tint = if (bookmarked) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = enter +
                if (motion) {
                    slideInVertically(tween(220, easing = ModernMotion.SmoothOutEasing)) { it / 5 }
                } else {
                    EnterTransition.None
                },
            exit = exit +
                if (motion) {
                    slideOutVertically(tween(160)) { it / 8 }
                } else {
                    ExitTransition.None
                },
        ) {
            Box(
                Modifier.fillMaxWidth()
                    .windowInsetsPadding(insets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                ReaderChromeSurface(
                    Modifier.widthIn(max = 560.dp).onSizeChanged {
                        bottomHeight = with(density) { it.height.toDp() } + 20.dp
                        onChromeSize(topHeight, bottomHeight)
                    },
                ) {
                    Column {
                        if (!vertical) {
                            ChapterNavigator(
                                isRtl, onNextChapter, enabledNext, onPreviousChapter, enabledPrevious,
                                currentPage, totalPages, onPageIndexChange, embedded = true,
                            )
                        }
                        BottomReaderBar(onChapters, onMode, onQuality, onMore, qualityComparison)
                    }
                }
            }
        }
        AnimatedVisibility(
            visible && vertical,
            modifier = Modifier.align(Alignment.CenterEnd)
                .windowInsetsPadding(insets.only(WindowInsetsSides.End)).padding(end = 12.dp),
            enter = enter,
            exit = exit,
        ) {
            VerticalChapterNavigator(
                currentPage,
                totalPages,
                enabledPrevious,
                enabledNext,
                onPreviousChapter,
                onNextChapter,
                onPageIndexChange,
                modifier = Modifier.width(68.dp).height(navigatorHeight),
            )
        }
    }
}

@Composable
private fun ReaderChromeSurface(modifier: Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .97f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f)),
        shadowElevation = 4.dp,
        content = content,
    )
}

package eu.kanade.presentation.discovery

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import eu.kanade.presentation.motion.PosterSource
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.presentation.motion.posterForeground
import eu.kanade.presentation.motion.posterNavigationRunning
import eu.kanade.presentation.motion.posterOpen
import eu.kanade.presentation.motion.rememberPosterSource
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.absoluteValue
import androidx.compose.ui.res.stringResource as androidStringResource

/** A finite lazy window with room to swipe in either direction, without a visible last/first jump. */
internal object PanoramaPages {
    const val WINDOW = 1_000_000
    const val INTERVAL_MILLIS = 6_000L
    fun index(page: Int, count: Int): Int = Math.floorMod(page, count.coerceAtLeast(1))
    fun initial(count: Int, selected: Int = 0): Int = if (count <= 1) {
        0
    } else {
        WINDOW / 2 - WINDOW / 2 % count + selected.coerceIn(0, count - 1)
    }
    fun coverWidth(width: Dp): Dp = (width * .76f).coerceAtMost(340.dp)
    fun stageHeight(width: Dp): Dp = coverWidth(width) / .72f + 32.dp
}

/** The body decides visibility; scrolling never launches or restarts a network request. */
@Composable
internal fun panoramaAutoplay(listState: LazyListState, key: String, active: Boolean = true): Boolean {
    val inset = with(androidx.compose.ui.platform.LocalDensity.current) { LocalHomeContentInset.current.roundToPx() }
    val visible by remember(listState, key, active, inset) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val item = layout.visibleItemsInfo.firstOrNull { it.key == key }
            val shown = item?.let {
                (
                    minOf(it.offset + it.size, layout.viewportEndOffset) -
                        maxOf(it.offset, layout.viewportStartOffset + inset)
                    )
                    .coerceAtLeast(0)
            } ?: 0
            active && !listState.isScrollInProgress && item != null && shown >= item.size * .6f
        }
    }
    return visible
}

/** Shared by extension video, extension manga and the optional catalogue fallback. */
@Composable
internal fun <T : Any> PanoramaCarousel(
    items: List<T>,
    itemKey: (T) -> String,
    title: (T) -> String,
    metadata: (T) -> String,
    artworkData: (T) -> Any,
    privacyModifier: (T) -> Modifier = { Modifier },
    heading: String = androidStringResource(R.string.home_panorama_featured),
    autoplay: Boolean = false,
    onOpen: (T) -> Unit,
    actions: @Composable (T) -> Unit = {},
    artwork: @Composable (T, Modifier, PosterSource) -> Unit,
) {
    if (items.isEmpty()) return
    val keys = items.map(itemKey)
    var selectedKey by rememberSaveable { mutableStateOf(keys.first()) }
    // Preserve the selected work when an extension refreshes, adds or reorders its featured titles.
    key(keys) {
        val pager = rememberPagerState(
            initialPage = PanoramaPages.initial(items.size, keys.indexOf(selectedKey).coerceAtLeast(0)),
            pageCount = { if (items.size == 1) 1 else PanoramaPages.WINDOW },
        )
        val motion = appMotionEnabled()
        val navigating = posterNavigationRunning()
        val active = LocalHomeContentActive.current
        val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
        val touchExploration = panoramaTouchExploration()
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        var touching by remember { mutableStateOf(false) }
        val current by remember { derivedStateOf { PanoramaPages.index(pager.currentPage, items.size) } }
        val scrolling by remember { derivedStateOf { pager.isScrollInProgress } }
        val canRotate = autoplay &&
            active &&
            windowFocused &&
            motion &&
            !navigating &&
            !touching &&
            !touchExploration &&
            !scrolling &&
            items.size > 1 &&
            lifecycle.isAtLeast(Lifecycle.State.RESUMED)
        LaunchedEffect(pager) {
            snapshotFlow { pager.settledPage }.distinctUntilChanged().collect {
                selectedKey = keys[PanoramaPages.index(it, items.size)]
            }
        }
        val readyToRotate by rememberUpdatedState(canRotate)
        LaunchedEffect(pager, lifecycle, motion, active) {
            if (!motion || !active || !lifecycle.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
            var idleMillis = 0L
            while (true) {
                delay(250)
                idleMillis = if (readyToRotate) idleMillis + 250 else 0
                if (idleMillis < PanoramaPages.INTERVAL_MILLIS) continue
                idleMillis = 0
                try {
                    if (pager.currentPage >= PanoramaPages.WINDOW - items.size * 2 ||
                        pager.currentPage < items.size * 2
                    ) {
                        val selected = PanoramaPages.index(pager.currentPage, items.size)
                        pager.scrollToPage(PanoramaPages.initial(items.size, selected))
                    }
                    pager.animateScrollToPage(
                        pager.currentPage + 1,
                        animationSpec = tween(460, easing = FastOutSlowInEasing),
                    )
                } catch (cancelled: CancellationException) {
                    // A swipe may take over the pager; disposal must still cancel this coroutine.
                    currentCoroutineContext().ensureActive()
                }
            }
        }
        val positionLabel = androidStringResource(R.string.home_featured_position, heading, current + 1, items.size)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            BoxWithConstraints(Modifier.widthIn(max = 600.dp).fillMaxWidth()) {
                val coverWidth = PanoramaPages.coverWidth(maxWidth)
                val inset = (maxWidth - coverWidth) / 2
                HorizontalPager(
                    state = pager,
                    pageSize = PageSize.Fixed(coverWidth),
                    contentPadding = PaddingValues(horizontal = inset, vertical = 16.dp),
                    beyondViewportPageCount = if (items.size > 1) 1 else 0,
                    userScrollEnabled = items.size > 1 && !navigating,
                    key = { it },
                    modifier = Modifier.fillMaxWidth().height(PanoramaPages.stageHeight(maxWidth))
                        .semantics {
                            collectionInfo = CollectionInfo(1, items.size)
                            stateDescription = positionLabel
                            horizontalScrollAxisRange = ScrollAxisRange(
                                value = { current.toFloat() },
                                maxValue = { (items.size - 1).toFloat() },
                            )
                        }.pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                touching = true
                                try {
                                    do {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                    } while (event.changes.any { it.pressed })
                                } finally {
                                    touching = false
                                }
                            }
                        },
                ) { page ->
                    val item = items[PanoramaPages.index(page, items.size)]
                    val poster = rememberPosterSource(artworkData(item), RoundedCornerShape(24.dp))
                    val open = posterOpen(poster, title(item)) { onOpen(item) }
                    val centered = page == pager.currentPage
                    Box(
                        Modifier.fillMaxSize().then(privacyModifier(item)).graphicsLayer {
                            val offset = ((pager.currentPage - page) + pager.currentPageOffsetFraction)
                                .coerceIn(-2f, 2f)
                            val distance = offset.absoluteValue
                            scaleX = 1f - .18f * distance
                            scaleY = scaleX
                            alpha = (1f - .46f * distance).coerceAtLeast(0f)
                            rotationY = if (motion) offset.coerceIn(-1f, 1f) * 8f else 0f
                            cameraDistance = 24f * density
                        }.then(if (centered) Modifier else Modifier.clearAndSetSemantics {})
                            .clip(RoundedCornerShape(24.dp))
                            .clickable(enabled = centered && !scrolling, role = Role.Button, onClick = open),
                    ) {
                        artwork(item, Modifier.fillMaxSize(), poster)
                        if (centered) {
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(8.dp).posterForeground(poster)
                                    .graphicsLayer {
                                        alpha = if (motion) {
                                            (1f - pager.currentPageOffsetFraction.absoluteValue * 2f).coerceIn(0f, 1f)
                                        } else {
                                            1f
                                        }
                                    },
                            ) {
                                actions(item)
                            }
                        }
                    }
                }
            }
            val titleStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
            val metaStyle = MaterialTheme.typography.bodySmall
            val captionHeight = with(LocalDensity.current) {
                titleStyle.lineHeight.toDp() * 2 + metaStyle.lineHeight.toDp() + 12.dp
            }
            Column(
                Modifier.widthIn(
                    max = 600.dp,
                ).fillMaxWidth().then(privacyModifier(items[current])).height(captionHeight)
                    .padding(horizontal = 28.dp).graphicsLayer {
                        alpha = if (motion) {
                            (1f - pager.currentPageOffsetFraction.absoluteValue * 2f).coerceIn(0f, 1f)
                        } else {
                            1f
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    title(items[current]),
                    style = titleStyle,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    metadata(items[current]),
                    Modifier.padding(top = 6.dp),
                    style = metaStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun panoramaTouchExploration(): Boolean {
    val manager = LocalContext.current.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    var enabled by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

@Composable
internal fun homeSectionHeaderHeight(): Dp = with(LocalDensity.current) {
    (MaterialTheme.typography.titleMedium.lineHeight.toDp() * 2 + 16.dp).coerceAtLeast(64.dp)
}

@Composable
internal fun PanoramaHeroSkeleton() {
    HomeSkeleton {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            BoxWithConstraints(Modifier.widthIn(max = 600.dp).fillMaxWidth()) {
                val coverWidth = PanoramaPages.coverWidth(maxWidth)
                Box(
                    Modifier.fillMaxWidth().height(PanoramaPages.stageHeight(maxWidth)).clipToBounds(),
                    contentAlignment = Alignment.Center,
                ) {
                    listOf(-1, 1, 0).forEach { side ->
                        Box(
                            Modifier.offset(x = coverWidth * side).width(coverWidth).height(coverWidth / .72f)
                                .graphicsLayer {
                                    scaleX = if (side == 0) 1f else .82f
                                    scaleY = scaleX
                                    alpha = if (side == 0) 1f else .54f
                                }.clip(RoundedCornerShape(24.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        )
                    }
                }
            }
            val captionHeight = with(LocalDensity.current) {
                MaterialTheme.typography.titleLarge.lineHeight.toDp() *
                    2 +
                    MaterialTheme.typography.bodySmall.lineHeight.toDp() +
                    12.dp
            }
            Column(
                Modifier.height(captionHeight),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SkeletonBlock(Modifier.width(180.dp).height(24.dp))
                SkeletonBlock(Modifier.padding(top = 12.dp).width(120.dp).height(16.dp))
            }
        }
    }
}

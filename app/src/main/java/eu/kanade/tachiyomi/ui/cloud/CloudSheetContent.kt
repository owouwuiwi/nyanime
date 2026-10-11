package eu.kanade.tachiyomi.ui.cloud

import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun CloudSheetContent(
    isTabletUi: Boolean,
    enableSwipeDismiss: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    animationDurationMillis: Int = 350,
    waitForDismissAnimation: Boolean = false,
    dismissAnimationDurationMillis: Int = animationDurationMillis,
    animationEasing: Easing = FastOutSlowInEasing,
    surfaceScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val animationSpec =
        remember(animationDurationMillis, animationEasing) {
            tween<Float>(animationDurationMillis, easing = animationEasing)
        }
    val dismissSpec =
        remember(dismissAnimationDurationMillis, animationEasing) {
            tween<Float>(dismissAnimationDurationMillis, easing = animationEasing)
        }
    val latestOnDismiss by rememberUpdatedState(onDismissRequest)
    val maxWidth = if (LocalConfiguration.current.orientation == ORIENTATION_LANDSCAPE) {
        600.dp
    } else {
        460.dp
    }

    if (isTabletUi) {
        val alpha = remember { Animatable(0f) }
        var closing by remember { mutableStateOf(false) }
        val internalOnDismissRequest: () -> Unit = {
            if (!closing) {
                closing = true
                scope.launch {
                    if (waitForDismissAnimation) alpha.animateTo(0f, dismissSpec) else alpha.snapTo(0f)
                    latestOnDismiss()
                }
            }
        }
        Box(
            modifier = Modifier
                .inactiveSheetContent(closing)
                .clickable(
                    interactionSource = null,
                    indication = null,
                    onClick = internalOnDismissRequest,
                )
                .fillMaxSize()
                .graphicsLayer { this.alpha = alpha.value },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .requiredWidthIn(max = maxWidth)
                    .graphicsLayer {
                        scaleX = surfaceScale + (1f - surfaceScale) * alpha.value
                        scaleY = scaleX
                    }
                    .clickable(
                        interactionSource = null,
                        indication = null,
                        onClick = {},
                    )
                    .systemBarsPadding()
                    .padding(vertical = 16.dp)
                    .then(modifier),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                content = {
                    BackHandler(onBack = internalOnDismissRequest)
                    content()
                },
            )

            LaunchedEffect(animationDurationMillis) {
                if (!closing) alpha.animateTo(1f, animationSpec)
            }
        }
    } else {
        val decayAnimationSpec = rememberSplineBasedDecay<Float>()
        val anchoredDraggableState = remember {
            AnchoredDraggableState(
                initialValue = 1,
                positionalThreshold = { with(density) { 56.dp.toPx() } },
                velocityThreshold = { with(density) { 125.dp.toPx() } },
                snapAnimationSpec = dismissSpec,
                decayAnimationSpec = decayAnimationSpec,
            )
        }
        var dismissed by remember { mutableStateOf(false) }
        var closing by remember { mutableStateOf(false) }
        val finishDismiss = {
            if (!dismissed) {
                dismissed = true
                latestOnDismiss()
            }
        }
        val internalOnDismissRequest: () -> Unit = {
            if (!closing && !dismissed) {
                closing = true
                scope.launch {
                    snapshotFlow { anchoredDraggableState.offset.isFinite() }.first { it }
                    anchoredDraggableState.animateTo(1, dismissSpec)
                    finishDismiss()
                }
            }
        }
        Box(
            modifier = Modifier
                .inactiveSheetContent(closing || !anchoredDraggableState.offset.isFinite())
                .clickable(
                    interactionSource = null,
                    indication = null,
                    onClick = internalOnDismissRequest,
                )
                .fillMaxSize()
                .onSizeChanged {
                    val anchors = DraggableAnchors {
                        0 at 0f
                        1 at it.height.toFloat()
                    }
                    anchoredDraggableState.updateAnchors(anchors, newTarget = anchoredDraggableState.targetValue)
                },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = maxWidth)
                    .clickable(
                        interactionSource = null,
                        indication = null,
                        onClick = {},
                    )
                    .then(
                        if (enableSwipeDismiss) {
                            Modifier.nestedScroll(
                                remember(anchoredDraggableState) {
                                    anchoredDraggableState.preUpPostDownNestedScrollConnection(
                                        onFling = { scope.launch { anchoredDraggableState.settle(it) } },
                                    )
                                },
                            )
                        } else {
                            Modifier
                        },
                    )
                    .then(modifier)
                    .graphicsLayer { alpha = if (anchoredDraggableState.offset.isFinite()) 1f else 0f }
                    .offset {
                        IntOffset(
                            0,
                            anchoredDraggableState.offset
                                .takeIf { it.isFinite() }
                                ?.roundToInt()
                                ?: 0,
                        )
                    }
                    .anchoredDraggable(
                        state = anchoredDraggableState,
                        orientation = Orientation.Vertical,
                        enabled = enableSwipeDismiss && !closing,
                    )
                    .navigationBarsPadding()
                    .statusBarsPadding(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                content = {
                    BackHandler(
                        enabled = !dismissed,
                        onBack = internalOnDismissRequest,
                    )
                    content()
                },
            )

            LaunchedEffect(anchoredDraggableState) {
                scope.launch {
                    snapshotFlow { anchoredDraggableState.offset.isFinite() }.first { it }
                    if (!closing) anchoredDraggableState.animateTo(0, animationSpec)
                }
                // Observe actual visibility: an opening interrupted by a swipe can return to
                // the hidden anchor without ever changing settledValue from its initial value.
                snapshotFlow {
                    anchoredDraggableState.offset < anchoredDraggableState.anchors.positionOf(1)
                }
                    .dropWhile { !it }
                    .filter { !it }
                    .collectLatest {
                        finishDismiss()
                    }
            }
            LaunchedEffect(animationDurationMillis) {
                if (animationDurationMillis == 0) {
                    snapshotFlow { anchoredDraggableState.offset.isFinite() }.first { it }
                    anchoredDraggableState.animateTo(if (closing) 1 else 0, tween(0))
                    if (closing) finishDismiss()
                }
            }
        }
    }
}

/** Dismissed surfaces may finish drawing, but cannot accept another content action. */
private fun Modifier.inactiveSheetContent(inactive: Boolean): Modifier = if (inactive) {
    clearAndSetSemantics {}.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
} else {
    this
}

private fun <T> AnchoredDraggableState<T>.preUpPostDownNestedScrollConnection(
    onFling: (velocity: Float) -> Unit,
) = object : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val delta = available.toFloat()
        return if (delta < 0 && source == NestedScrollSource.UserInput) {
            dispatchRawDelta(delta).toOffset()
        } else {
            Offset.Zero
        }
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        return if (source == NestedScrollSource.UserInput) {
            dispatchRawDelta(available.toFloat()).toOffset()
        } else {
            Offset.Zero
        }
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        val toFling = available.toFloat()
        return if (toFling < 0 && offset > anchors.minPosition()) {
            onFling(toFling)
            // since we go to the anchor with tween settling, consume all for the best UX
            available
        } else {
            Velocity.Zero
        }
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        onFling(available.toFloat())
        return available
    }

    private fun Float.toOffset(): Offset = Offset(0f, this)

    @JvmName("velocityToFloat")
    private fun Velocity.toFloat() = this.y

    @JvmName("offsetToFloat")
    private fun Offset.toFloat(): Float = this.y
}

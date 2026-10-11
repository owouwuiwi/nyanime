package eu.kanade.presentation.motion

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import cafe.adriel.voyager.navigator.LocalNavigator
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import eu.kanade.presentation.discovery.LocalHomeContinuity
import eu.kanade.presentation.discovery.sourceHomeArtworkIdentity
import java.util.UUID

internal const val POSTER_TRANSITION_MILLIS = 360

internal typealias PosterDestinationSlot = @Composable (@Composable () -> Unit) -> Unit

@OptIn(ExperimentalSharedTransitionApi::class)
internal data class PosterScene(
    val state: PosterNavigationState<Painter>,
    val shared: SharedTransitionScope,
    val visibility: AnimatedVisibilityScope,
    val route: String,
    val active: PosterRoute<Painter>?,
    val running: Boolean,
    val enabled: Boolean,
    val destinationContent: PosterDestinationSlot,
    val detailsContent: PosterDestinationSlot,
)

@Suppress("CompositionLocalAllowlist")
// Navigation changes this value at both ends of each transition. Track readers rather than
// invalidating every descendant, including lists and the fixed Home header that do not use it.
internal val LocalPosterScene = compositionLocalOf<PosterScene?> { null }

/** Uses the navigator's real transition so interruption, pop and screen disposal have one owner. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun <T> PosterNavigationTransition(
    navigation: Transition<T>,
    routeKey: (T) -> String,
    retainedRoutes: Set<String>,
    enabled: Boolean,
    transitionSpec: AnimatedContentTransitionScope<T>.() -> ContentTransform,
    modifier: Modifier = Modifier,
    state: PosterNavigationState<Painter> = remember { PosterNavigationState() },
    content: @Composable AnimatedVisibilityScope.(T) -> Unit,
) {
    val currentKey = routeKey(navigation.currentState)
    val targetKey = routeKey(navigation.targetState)
    val active = state.between(currentKey, targetKey).takeIf { enabled }
    val moving = currentKey != targetKey || navigation.isRunning
    LaunchedEffect(retainedRoutes, moving, enabled) {
        if (!enabled) {
            state.clear()
        } else if (!moving) {
            state.retain(retainedRoutes)
        }
    }
    SharedTransitionLayout(modifier) {
        val shared = this
        navigation.AnimatedContent(
            transitionSpec = {
                if (enabled && state.between(routeKey(initialState), routeKey(targetState)) != null) {
                    ModernMotion.transform(true, POSTER_TRANSITION_MILLIS).using(null)
                } else {
                    transitionSpec()
                }
            },
            contentKey = routeKey,
        ) { screen ->
            val key = routeKey(screen)
            // Loading and resolved detail screens own different composition branches. Keep
            // their primary artwork and caption in one slot so neither restarts mid-flight.
            val destinationContent = remember(key) {
                movableContentOf<@Composable () -> Unit> { child -> child() }
            }
            val detailsContent = remember(key) {
                movableContentOf<@Composable () -> Unit> { child -> child() }
            }
            CompositionLocalProvider(
                LocalPosterScene provides PosterScene(
                    state,
                    shared,
                    this,
                    key,
                    active,
                    running = moving,
                    enabled = enabled,
                    destinationContent = destinationContent,
                    detailsContent = detailsContent,
                ),
            ) {
                content(screen)
            }
        }
    }
}

@Composable
internal fun PosterDestinationContent(content: @Composable () -> Unit) {
    val scene = LocalPosterScene.current
    if (scene?.enabled == true) scene.destinationContent(content) else content()
}

/** Keep the page's lazy-list state and loading slots when its model becomes available. */
@Composable
internal fun PosterDetailsContent(content: @Composable () -> Unit) {
    val scene = LocalPosterScene.current
    if (scene != null) scene.detailsContent(content) else content()
}

internal class PosterSource(val element: String, val shape: Shape = RectangleShape, val backdrop: HazeState? = null) {
    var painter by mutableStateOf<Painter?>(null)
}

/** The saved token identifies a particular card, including its position in a particular row. */
@Composable
internal fun rememberPosterSource(data: Any?, shape: Shape = RectangleShape): PosterSource {
    val identity = data?.let(::sourceHomeArtworkIdentity)
    val token = rememberSaveable(identity) { UUID.randomUUID().toString() }
    val backdrop = LocalHomeContinuity.current?.backdrop
    return remember(token, shape, backdrop) { PosterSource(token, shape, backdrop) }
}

@Composable
internal fun posterOpen(source: PosterSource, title: String, onClick: () -> Unit): () -> Unit {
    val scene = LocalPosterScene.current
    val navigator = LocalNavigator.current
    val retained = posterSourcePlaceholder(source)
    SideEffect {
        if (source.painter == null && retained != null) source.painter = retained
    }
    return {
        // A second tap on the outgoing page must not push the same destination twice.
        if (scene?.enabled != true || navigator == null || navigator.lastItem.key == scene.route) {
            val before = navigator?.lastItem
            onClick()
            val after = navigator?.lastItem
            val image = source.painter ?: retained
            if (scene?.enabled == true &&
                image != null &&
                after is PosterDetailsScreen &&
                before?.key == scene.route &&
                after.key != before.key
            ) {
                scene.state.connect(
                    before.key,
                    after.key,
                    source.element,
                    title,
                    image,
                    source.shape,
                    after.posterDestinationShape,
                    source.backdrop,
                )
            }
        }
    }
}

@Composable
internal fun posterSourcePlaceholder(source: PosterSource?): Painter? {
    if (source == null) return null
    source.painter?.let { return it }
    val scene = LocalPosterScene.current ?: return null
    return scene.active?.takeIf { it.origin == scene.route && it.element == source.element }?.artwork
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.posterSource(source: PosterSource?): Modifier {
    if (source == null) return this
    val background = MaterialTheme.colorScheme.surfaceContainerHigh
    val scene = LocalPosterScene.current ?: return this.background(background)
    if (!scene.enabled) return this.background(background)
    val overlayClip = rememberPosterOverlayClip(scene, source.shape, source = true)
    val route = scene.active?.takeIf { it.origin == scene.route && it.element == source.element }
    // Home may have been recreated while the detail route was open: use its live backdrop.
    val capture = source.backdrop?.takeIf {
        route != null && scene.running && scene.visibility.transition.targetState == EnterExitState.Visible
    }
    return with(scene.shared) {
        this@posterSource.sharedElement(
            sharedContentState = rememberSharedContentState(source.element),
            animatedVisibilityScope = scene.visibility,
            boundsTransform = { _, _ -> tween(POSTER_TRANSITION_MILLIS, easing = FastOutSlowInEasing) },
            clipInOverlayDuringTransition = overlayClip,
        )
            .then(if (capture != null) Modifier.hazeSource(capture, zIndex = 1f) else Modifier)
            .background(background)
            .posterShade(route?.shade, source = true)
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.posterDestination(shade: Brush? = null): Modifier {
    val scene = LocalPosterScene.current ?: return this
    val origin = scene.state.destination(scene.route) ?: return this
    if (!scene.enabled) return this
    SideEffect { origin.shade = shade }
    val overlayClip = rememberPosterOverlayClip(scene, origin.shape, source = false)
    val capture = origin.backdrop?.takeIf {
        scene.running && scene.visibility.transition.targetState == EnterExitState.Visible
    }
    return with(scene.shared) {
        this@posterDestination.sharedElement(
            sharedContentState = rememberSharedContentState(origin.element),
            animatedVisibilityScope = scene.visibility,
            boundsTransform = { _, _ -> tween(POSTER_TRANSITION_MILLIS, easing = FastOutSlowInEasing) },
            clipInOverlayDuringTransition = overlayClip,
        )
            .then(if (capture != null) Modifier.hazeSource(capture, zIndex = 1f) else Modifier)
    }
}

/** The same shading belongs to the travelling image at both ends, with the bounds' animation clock. */
@Composable
internal fun Modifier.posterShade(shade: Brush?, source: Boolean = false): Modifier {
    if (shade == null) return this
    val scene = LocalPosterScene.current
    val opacity = if (scene?.enabled == true && scene.active != null) {
        scene.visibility.transition.animateFloat(
            transitionSpec = { tween(POSTER_TRANSITION_MILLIS, easing = FastOutSlowInEasing) },
            label = "posterShade",
        ) { visibility -> if ((visibility == EnterExitState.Visible) != source) 1f else 0f }
    } else {
        remember(source) { mutableStateOf(if (source) 0f else 1f) }
    }
    return drawWithCache {
        onDrawWithContent {
            drawContent()
            if (opacity.value > 0f) drawRect(shade, alpha = opacity.value)
        }
    }
}

/** One retained image changes aspect ratio; clipping follows the animated bounds, not a scaled child. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun rememberPosterOverlayClip(
    scene: PosterScene,
    shape: Shape,
    source: Boolean,
): SharedTransitionScope.OverlayClip {
    val destinationShape = scene.active?.destinationShape ?: RectangleShape
    if (shape == RectangleShape && destinationShape == RectangleShape) {
        return remember(scene.shared) { scene.shared.OverlayClip(RectangleShape) }
    }
    val corners = scene.visibility.transition.animateFloat(
        transitionSpec = { tween(POSTER_TRANSITION_MILLIS, easing = FastOutSlowInEasing) },
        label = "posterCorners",
    ) { visibility -> if ((visibility == EnterExitState.Visible) == source) 1f else 0f }
    return remember(scene.shared, shape, destinationShape, corners) {
        scene.shared.OverlayClip(PosterOverlayShape(shape, destinationShape, corners))
    }
}

/** Read animation state while drawing, without recomposing Home on every frame. */
internal class PosterOverlayShape(val source: Shape, val destination: Shape, val fraction: State<Float>) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val outline = source.createOutline(size, layoutDirection, density)
        val destinationOutline = destination.createOutline(size, layoutDirection, density)
        val round = when (outline) {
            is Outline.Rounded -> outline.roundRect
            is Outline.Rectangle -> RoundRect(outline.rect)
            else -> return outline
        }
        val target = when (destinationOutline) {
            is Outline.Rounded -> destinationOutline.roundRect
            is Outline.Rectangle -> RoundRect(destinationOutline.rect)
            else -> return destinationOutline
        }
        val value = fraction.value.coerceIn(0f, 1f)
        fun corner(from: CornerRadius, to: CornerRadius) = CornerRadius(
            to.x + (from.x - to.x) * value,
            to.y + (from.y - to.y) * value,
        )
        return Outline.Rounded(
            RoundRect(
                round.left,
                round.top,
                round.right,
                round.bottom,
                corner(round.topLeftCornerRadius, target.topLeftCornerRadius),
                corner(round.topRightCornerRadius, target.topRightCornerRadius),
                corner(round.bottomRightCornerRadius, target.bottomRightCornerRadius),
                corner(round.bottomLeftCornerRadius, target.bottomLeftCornerRadius),
            ),
        )
    }
}

@Composable
internal fun posterDetailPreview(): PosterRoute<Painter>? {
    val scene = LocalPosterScene.current ?: return null
    return scene.state.destination(scene.route).takeIf { scene.enabled }
}

@Composable
internal fun posterNavigationRunning(): Boolean = LocalPosterScene.current?.running == true

@Composable
internal fun posterRequestsEnabled(): Boolean {
    val scene = LocalPosterScene.current ?: return true
    return !scene.enabled || !scene.running || scene.state.destination(scene.route) == null
}

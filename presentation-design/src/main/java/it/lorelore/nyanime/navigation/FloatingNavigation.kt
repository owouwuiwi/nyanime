package it.lorelore.nyanime.navigation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as lerpDp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

private const val ACTIVE_COLOR_MAX_OFFSET = .48f
private const val LIGHT_GLASS_TINT_OPACITY = .68f
private const val DARK_GLASS_TINT_OPACITY = .63f
private const val BAR_BORDER_RED_BLEND = .16f
private const val UNDERLAY_TOP_OPACITY = .61f
private const val UNDERLAY_BOTTOM_OPACITY = .77f
private const val LIFT_WIDTH_RATIO = .16f
private const val LIFT_HEIGHT_RATIO = .13f
private const val ELASTIC_WIDTH_RATIO = .22f
private const val ELASTIC_HEIGHT_RATIO = .18f
private const val ELASTIC_ORIGIN_SHIFT = .23f
private const val TRAVEL_LIFT = .72f
private const val LIFT_RELEASE_TENSION = .35f
private const val FULL_STRETCH_DISTANCE_IN_TABS = .22f
private const val LANDING_EDGE_OVERTRAVEL_IN_TABS = .05f
private const val RELEASE_LOOK_AHEAD_SECONDS = .08f
private const val MAX_RELEASE_TRAVEL_IN_TABS = .45f
private const val RESTING_HIGHLIGHT_OPACITY = .74f
private const val SELECTOR_LIGHT_ANGLE = 48f
private const val SELECTOR_LIGHT_SWEEP = 32f

private val barShape = RoundedCornerShape(26.dp)
private val barElevation = 6.dp
private val glassEdgeWidth = 1.dp
private val barBlur = 7.dp
private val barRefractionDepth = 18.dp
private val barRefractionDistance = 20.dp
private val barHighlight = Highlight(width = glassEdgeWidth, style = HighlightStyle.Default(angle = 55f))
private val barShadow = Shadow(radius = 16.dp, color = Color.Black.copy(alpha = .24f))
private val indicatorSpacing = 2.dp
private val indicatorCornerRadius = 20.dp
private val liftedCornerRadius = 34.dp
private val indicatorShape = RoundedCornerShape(indicatorCornerRadius)
private val indicatorUnderlayInset = 3.dp
private val selectorRefractionDepth = 14.dp
private val selectorRefractionDistance = 17.dp
private val selectorLiftRefraction = 7.dp
private val selectorLight = HighlightStyle.Default(angle = SELECTOR_LIGHT_ANGLE)
private val selectorHighlight = Highlight(width = 1.5.dp, style = selectorLight)
private val selectorInnerShadow = InnerShadow(radius = 5.dp, color = Color.Black.copy(alpha = .26f))
private val selectorShadow = Shadow(radius = 9.dp, color = Color.Black.copy(alpha = .31f))
private val deepRed = Color(0xFF790E26)
private val lensRed = Color(0xFFA72137)
private val redUnderlay = Brush.verticalGradient(
    listOf(lensRed.copy(alpha = UNDERLAY_TOP_OPACITY), deepRed.copy(alpha = UNDERLAY_BOTTOM_OPACITY)),
)
private val solidTint = Brush.verticalGradient(listOf(lensRed, deepRed))
private val fingerTracking = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 820f)
private val lensRelease = spring<Float>(dampingRatio = .84f, stiffness = Spring.StiffnessMediumLow)
private val lensLift = spring<Float>(dampingRatio = .85f, stiffness = Spring.StiffnessMediumLow)

class FloatingNavigationItem(
    val label: String,
    val testTag: String,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)?,
    val longClickLabel: String?,
    val icon: @Composable () -> Unit,
)

@Composable
fun FloatingNavigationSurface(backdrop: Backdrop?, content: @Composable (Boolean, Backdrop?) -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    val body = colorScheme.surfaceContainerHigh
    val railBackdrop = if (backdrop != null) rememberLayerBackdrop() else null
    Box(
        Modifier.fillMaxWidth()
            .navigationBarsPadding()
            .padding(
                start = 12.dp,
                end = 12.dp,
                top = FloatingNavigationLayout.topMargin,
                bottom = FloatingNavigationLayout.bottomMargin,
            ),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 350.dp || LocalDensity.current.fontScale > 1.3f
            val surface = if (backdrop != null) {
                val opacity = if (body.luminance() > .5f) LIGHT_GLASS_TINT_OPACITY else DARK_GLASS_TINT_OPACITY
                Modifier.drawBackdrop(
                    backdrop = backdrop,
                    shape = { barShape },
                    effects = {
                        vibrancy()
                        blur(barBlur.toPx())
                        lens(barRefractionDepth.toPx(), barRefractionDistance.toPx(), depthEffect = true)
                    },
                    highlight = { barHighlight },
                    shadow = { barShadow },
                    exportedBackdrop = railBackdrop,
                    onDrawSurface = { drawRect(body.copy(alpha = opacity)) },
                )
            } else {
                Modifier.shadow(barElevation, barShape, clip = false)
                    .background(body, barShape)
                    .border(glassEdgeWidth, lerp(colorScheme.outlineVariant, lensRed, BAR_BORDER_RED_BLEND), barShape)
            }
            Box(Modifier.matchParentSize().then(surface))
            Row(
                Modifier.fillMaxWidth().padding(FloatingNavigationLayout.contentPadding),
                horizontalArrangement = Arrangement.spacedBy(indicatorSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                content(compact, railBackdrop)
            }
        }
    }
}

@Composable
fun FloatingNavigationItems(
    items: List<FloatingNavigationItem>,
    selectedIndex: Int,
    compact: Boolean,
    elasticMotion: Boolean,
    backdrop: Backdrop?,
) {
    if (items.isEmpty()) return
    var pressedIndex by remember { mutableStateOf<Int?>(null) }
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    var width by remember { mutableIntStateOf(0) }
    val gap = with(LocalDensity.current) { indicatorSpacing.roundToPx() }
    val step = (width + gap).toFloat() / items.size
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val currentItems by rememberUpdatedState(items)
    val dragTarget = dragPosition ?: (pressedIndex ?: selectedIndex).coerceAtLeast(0).toFloat()
    val indicatorPosition = animateFloatAsState(
        targetValue = dragTarget,
        animationSpec = when {
            !elasticMotion -> snap()
            dragPosition != null -> fingerTracking
            else -> lensRelease
        },
        label = "navigationIndicator",
    )
    val target by rememberUpdatedState(dragTarget)
    val rawTension = remember(indicatorPosition, direction, elasticMotion) {
        derivedStateOf {
            if (!elasticMotion) return@derivedStateOf 0f
            val distance = direction * (target - indicatorPosition.value)
            (distance / FULL_STRETCH_DISTANCE_IN_TABS).coerceIn(-1f, 1f)
        }
    }
    val traveling by remember(rawTension) { derivedStateOf { rawTension.value.absoluteValue > LIFT_RELEASE_TENSION } }
    val lift = animateFloatAsState(
        targetValue = when {
            !elasticMotion -> 0f
            pressedIndex != null || dragPosition != null -> 1f
            traveling -> TRAVEL_LIFT
            else -> 0f
        },
        animationSpec = if (elasticMotion) lensLift else snap(),
        label = "navigationLensLift",
    )
    // Both inputs are already animated, so the lens can deform without restarting a spring per frame.
    val stretch = remember(rawTension, lift) { derivedStateOf { rawTension.value * lift.value } }
    val press = Modifier.pointerInput(step, direction, items.size) {
        if (step <= 0f) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val x = if (direction > 0f) down.position.x else size.width - down.position.x
            pressedIndex = (x / step).toInt().coerceIn(items.indices)
            try {
                // Move the lens on touch-down while the button owns selection and long press.
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                } while (event.changes.any { it.pressed })
            } finally {
                pressedIndex = null
            }
        }
    }
    val drag = if (selectedIndex in items.indices && items.size > 1 && step > 0f) {
        Modifier.pointerInput(selectedIndex, items.size, step, direction) {
            val velocity = VelocityTracker()
            detectHorizontalDragGestures(
                onDragStart = {
                    velocity.resetTracking()
                    dragPosition = pressedIndex?.toFloat() ?: indicatorPosition.value
                },
                onDragEnd = {
                    val releaseTravel = (direction * velocity.calculateVelocity().x / step * RELEASE_LOOK_AHEAD_SECONDS)
                        .coerceIn(-MAX_RELEASE_TRAVEL_IN_TABS, MAX_RELEASE_TRAVEL_IN_TABS)
                    val destination = dragPosition?.let { (it + releaseTravel).roundToInt().coerceIn(currentItems.indices) }
                    dragPosition = null
                    if (destination != null && destination != selectedIndex) currentItems[destination].onClick()
                },
                onDragCancel = { dragPosition = null },
            ) { change, amount ->
                change.consume()
                velocity.addPosition(change.uptimeMillis, change.position)
                dragPosition = ((dragPosition ?: selectedIndex.toFloat()) + direction * amount / step)
                    .coerceIn(0f, items.lastIndex.toFloat())
            }
        }
    } else {
        Modifier
    }
    Box(Modifier.fillMaxWidth().onSizeChanged { width = it.width }.then(press).then(drag)) {
        if (selectedIndex in items.indices) {
            val edgeOvertravel = if (elasticMotion && dragPosition == null) LANDING_EDGE_OVERTRAVEL_IN_TABS else 0f
            val lensBackdrop = backdrop?.let { content ->
                val underlay = rememberCanvasBackdrop {
                    val inset = indicatorUnderlayInset.toPx()
                    if (size.minDimension > inset * 2) {
                        drawRoundRect(
                            brush = redUnderlay,
                            topLeft = Offset(inset, inset),
                            size = Size(size.width - inset * 2, size.height - inset * 2),
                            cornerRadius = CornerRadius((selectorCorner(lift.value) - indicatorUnderlayInset).toPx()),
                        )
                    }
                }
                rememberCombinedBackdrop(content, underlay)
            }
            Box(
                Modifier.matchParentSize()
                    .layout { measurable, constraints ->
                        val available = (constraints.maxWidth - gap * (items.size - 1)).coerceAtLeast(0)
                        val cellWidth = available.toFloat() / items.size
                        val indicator = measurable.measure(Constraints.fixed(cellWidth.roundToInt(), constraints.maxHeight))
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            val position = indicatorPosition.value.coerceIn(-edgeOvertravel, items.lastIndex + edgeOvertravel)
                            indicator.placeRelative((position * (cellWidth + gap)).roundToInt(), 0)
                        }
                    }
                    .redGlassIndicator(lensBackdrop, lift, stretch),
            )
        }
        Row(
            Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(indicatorSpacing),
        ) {
            items.forEachIndexed { index, item ->
                FloatingNavigationButton(
                    item,
                    compact,
                    selected = selectedIndex == index,
                    index = index,
                    indicator = indicatorPosition.takeIf { selectedIndex in items.indices },
                )
            }
        }
    }
}

private fun selectorCorner(lift: Float) = lerpDp(indicatorCornerRadius, liftedCornerRadius, lift.coerceIn(0f, 1f))

private fun Modifier.redGlassIndicator(
    backdrop: Backdrop?,
    lift: State<Float>,
    tension: State<Float>,
): Modifier {
    val shape = {
        if (lift.value <= 0f) indicatorShape else RoundedCornerShape(selectorCorner(lift.value))
    }
    val deform: GraphicsLayerScope.() -> Unit = {
        val liftProgress = lift.value.coerceIn(0f, 1f)
        val stretch = tension.value * liftProgress
        scaleX = 1f + LIFT_WIDTH_RATIO * liftProgress + ELASTIC_WIDTH_RATIO * stretch.absoluteValue
        scaleY = 1f + LIFT_HEIGHT_RATIO * liftProgress - ELASTIC_HEIGHT_RATIO * stretch.absoluteValue
        transformOrigin = TransformOrigin(.5f - ELASTIC_ORIGIN_SHIFT * stretch, .5f)
    }
    if (backdrop == null) {
        return graphicsLayer {
            deform()
            this.shape = shape()
            clip = true
        }.background(solidTint)
    }
    return drawBackdrop(
        backdrop = backdrop,
        shape = shape,
        effects = {
            lens(
                refractionHeight = selectorRefractionDepth.toPx(),
                refractionAmount = selectorRefractionDistance.toPx() + selectorLiftRefraction.toPx() * lift.value.coerceIn(0f, 1f),
                depthEffect = true,
                chromaticAberration = true,
            )
        },
        highlight = {
            selectorHighlight.copy(
                alpha = RESTING_HIGHLIGHT_OPACITY + (1f - RESTING_HIGHLIGHT_OPACITY) * lift.value.coerceIn(0f, 1f),
                style = selectorLight.copy(angle = SELECTOR_LIGHT_ANGLE + SELECTOR_LIGHT_SWEEP * tension.value),
            )
        },
        shadow = { selectorShadow },
        innerShadow = { selectorInnerShadow },
        layerBlock = deform,
    )
}

@Composable
private fun RowScope.FloatingNavigationButton(
    item: FloatingNavigationItem,
    compact: Boolean,
    selected: Boolean,
    index: Int,
    indicator: State<Float>?,
) {
    val colorScheme = MaterialTheme.colorScheme
    val highlighted by remember(indicator, index) {
        derivedStateOf { indicator != null && (indicator.value - index).absoluteValue <= ACTIVE_COLOR_MAX_OFFSET }
    }
    val contentColor = if (highlighted) Color.White else colorScheme.onSurface
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val focusRing = if (focused) Modifier.border(glassEdgeWidth, colorScheme.primary, indicatorShape) else Modifier
    Column(
        Modifier.weight(1f)
            .heightIn(min = FloatingNavigationLayout.itemMinHeight(compact))
            .clip(indicatorShape)
            .then(focusRing)
            .combinedClickable(
                interactionSource = interactions,
                indication = null,
                onLongClick = item.onLongClick,
                onLongClickLabel = item.longClickLabel,
                onClick = item.onClick,
            )
            .semantics {
                role = Role.Tab
                this.selected = selected
                contentDescription = item.label
            }
            .testTag(item.testTag),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                item.icon()
            }
        }
        Text(
            item.label,
            modifier = Modifier.padding(horizontal = 3.dp),
            color = contentColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

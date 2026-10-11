package it.lorelore.nyanime.navigation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

private const val ACTIVE_COLOR_MAX_OFFSET = .48f
private const val BAR_SURFACE_OPACITY = .91f
private const val BAR_BORDER_RED_BLEND = .16f
private const val UNDERLAY_TOP_OPACITY = .80f
private const val UNDERLAY_BOTTOM_OPACITY = .89f
private const val REFLECTION_TOP_OPACITY = .12f
private const val REFLECTION_CLEAR_STOP = .35f
private const val REFLECTION_BOTTOM_OPACITY = .05f

private val barShape = RoundedCornerShape(26.dp)
private val barElevation = 6.dp
private val glassEdgeWidth = 1.dp
private val indicatorSpacing = 2.dp
private val indicatorCornerRadius = 20.dp
private val indicatorShape = RoundedCornerShape(indicatorCornerRadius)
private val indicatorUnderlayInset = 2.dp
private val glassBlurRadius = 3.dp
private val glassRefractionHeight = 9.dp
private val glassRefractionAmount = 16.dp
private val deepRed = Color(0xFF790E26)
private val lensRed = Color(0xFFA72137)
private val indicatorRim = Brush.verticalGradient(
    listOf(Color(0xEFFFDADE), Color(0x80F85E71), Color(0x902C0711)),
)
private val redUnderlay = Brush.verticalGradient(
    listOf(lensRed.copy(alpha = UNDERLAY_TOP_OPACITY), deepRed.copy(alpha = UNDERLAY_BOTTOM_OPACITY)),
)
private val solidTint = Brush.verticalGradient(listOf(lensRed, deepRed))
private val clearReflection = Brush.verticalGradient(
    0f to Color.White.copy(alpha = REFLECTION_TOP_OPACITY),
    REFLECTION_CLEAR_STOP to Color.Transparent,
    1f to Color.White.copy(alpha = REFLECTION_BOTTOM_OPACITY),
)

class FloatingNavigationItem(
    val label: String,
    val testTag: String,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)?,
    val longClickLabel: String?,
    val icon: @Composable () -> Unit,
)

@Composable
fun FloatingNavigationSurface(translucent: Boolean, content: @Composable (compact: Boolean) -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    val body = colorScheme.surfaceContainerHigh
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
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = barShape,
            color = if (translucent) body.copy(alpha = BAR_SURFACE_OPACITY) else body,
            border = BorderStroke(glassEdgeWidth, lerp(colorScheme.outlineVariant, lensRed, BAR_BORDER_RED_BLEND)),
            shadowElevation = barElevation,
        ) {
            BoxWithConstraints {
                val compact = maxWidth < 350.dp || LocalDensity.current.fontScale > 1.3f
                Row(
                    Modifier.fillMaxWidth().padding(FloatingNavigationLayout.contentPadding),
                    horizontalArrangement = Arrangement.spacedBy(indicatorSpacing),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    content(compact)
                }
            }
        }
    }
}

@Composable
fun FloatingNavigationItems(
    items: List<FloatingNavigationItem>,
    selectedIndex: Int,
    compact: Boolean,
    indicatorDurationMillis: Int,
    backdrop: Backdrop?,
) {
    if (items.isEmpty()) return
    val indicatorPosition = animateFloatAsState(
        targetValue = selectedIndex.coerceAtLeast(0).toFloat(),
        animationSpec = tween(indicatorDurationMillis),
        label = "navigationIndicator",
    )
    Box(Modifier.fillMaxWidth()) {
        if (selectedIndex >= 0) {
            val lensBackdrop = backdrop?.let { content ->
                val underlay = rememberCanvasBackdrop {
                    val inset = indicatorUnderlayInset.toPx()
                    if (size.minDimension > inset * 2) {
                        drawRoundRect(
                            brush = redUnderlay,
                            topLeft = Offset(inset, inset),
                            size = Size(size.width - inset * 2, size.height - inset * 2),
                            cornerRadius = CornerRadius((indicatorCornerRadius - indicatorUnderlayInset).toPx()),
                        )
                    }
                }
                rememberCombinedBackdrop(content, underlay)
            }
            Box(
                Modifier.matchParentSize()
                    .layout { measurable, constraints ->
                        val gap = indicatorSpacing.roundToPx()
                        val available = (constraints.maxWidth - gap * (items.size - 1)).coerceAtLeast(0)
                        val cellWidth = available.toFloat() / items.size
                        val indicator = measurable.measure(Constraints.fixed(cellWidth.roundToInt(), constraints.maxHeight))
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            indicator.placeRelative((indicatorPosition.value * (cellWidth + gap)).roundToInt(), 0)
                        }
                    }
                    .redGlassIndicator(lensBackdrop),
            )
        }
        Row(
            Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(indicatorSpacing),
        ) {
            items.forEachIndexed { index, item ->
                val overIndicator by remember(indicatorPosition, index) {
                    derivedStateOf { (indicatorPosition.value - index).absoluteValue <= ACTIVE_COLOR_MAX_OFFSET }
                }
                FloatingNavigationButton(
                    item,
                    compact,
                    selected = selectedIndex == index,
                    highlighted = selectedIndex >= 0 && overIndicator,
                )
            }
        }
    }
}

private fun Modifier.redGlassIndicator(backdrop: Backdrop?): Modifier {
    val glass = if (backdrop == null) {
        Modifier.background(solidTint, indicatorShape)
    } else {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { indicatorShape },
            effects = {
                if (size.minDimension > 0f) {
                    blur(glassBlurRadius.toPx())
                    lens(
                        refractionHeight = glassRefractionHeight.toPx(),
                        refractionAmount = glassRefractionAmount.toPx(),
                        depthEffect = true,
                    )
                }
            },
            highlight = null,
            shadow = null,
            onDrawSurface = { drawRect(clearReflection) },
        )
    }
    return this.border(glassEdgeWidth, indicatorRim, indicatorShape).then(glass)
}

@Composable
private fun RowScope.FloatingNavigationButton(
    item: FloatingNavigationItem,
    compact: Boolean,
    selected: Boolean,
    highlighted: Boolean,
) {
    val colorScheme = MaterialTheme.colorScheme
    val contentColor = if (highlighted) Color.White else colorScheme.onSurfaceVariant
    Column(
        Modifier.weight(1f)
            .heightIn(min = FloatingNavigationLayout.itemMinHeight(compact))
            .clip(indicatorShape)
            .combinedClickable(
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

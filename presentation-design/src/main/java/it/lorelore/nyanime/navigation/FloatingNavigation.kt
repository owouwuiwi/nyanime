package it.lorelore.nyanime.navigation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

class FloatingNavigationItem(
    val label: String,
    val testTag: String,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)?,
    val longClickLabel: String?,
    val icon: @Composable () -> Unit,
)

@Composable
fun FloatingNavigationSurface(content: @Composable (compact: Boolean) -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
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
            shape = RoundedCornerShape(26.dp),
            color = colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, colorScheme.outlineVariant),
            shadowElevation = 6.dp,
        ) {
            BoxWithConstraints {
                val compact = maxWidth < 350.dp || LocalDensity.current.fontScale > 1.3f
                Row(
                    Modifier.fillMaxWidth().padding(FloatingNavigationLayout.contentPadding),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
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
) {
    if (items.isEmpty()) return
    val indicatorPosition by animateFloatAsState(
        targetValue = selectedIndex.coerceAtLeast(0).toFloat(),
        animationSpec = tween(indicatorDurationMillis),
        label = "navigationIndicator",
    )
    val indicatorColor = MaterialTheme.colorScheme.primaryContainer
    val spacing = 2.dp
    Row(
        Modifier.fillMaxWidth()
            .selectableGroup()
            .drawWithCache {
                val gap = spacing.toPx()
                val cellWidth = ((size.width - gap * (items.size - 1)) / items.size).coerceAtLeast(0f)
                val radius = CornerRadius(20.dp.toPx())
                onDrawBehind {
                    if (selectedIndex >= 0) {
                        val fromStart = indicatorPosition * (cellWidth + gap)
                        val left = if (layoutDirection == LayoutDirection.Ltr) {
                            fromStart
                        } else {
                            size.width - cellWidth - fromStart
                        }
                        drawRoundRect(
                            color = indicatorColor,
                            topLeft = Offset(left, 0f),
                            size = Size(cellWidth, size.height),
                            cornerRadius = radius,
                        )
                    }
                }
            },
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        items.forEachIndexed { index, item ->
            FloatingNavigationButton(item, compact, selectedIndex == index)
        }
    }
}

@Composable
private fun RowScope.FloatingNavigationButton(
    item: FloatingNavigationItem,
    compact: Boolean,
    selected: Boolean,
) {
    val colorScheme = MaterialTheme.colorScheme
    val contentColor = if (selected) colorScheme.onPrimaryContainer else colorScheme.onSurfaceVariant
    Column(
        Modifier.weight(1f)
            .heightIn(min = FloatingNavigationLayout.itemMinHeight(compact))
            .clip(RoundedCornerShape(20.dp))
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

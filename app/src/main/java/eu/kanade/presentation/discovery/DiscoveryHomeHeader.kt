package eu.kanade.presentation.discovery

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Adjust
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.presentation.motion.modernMotionEnabled
import eu.kanade.presentation.motion.posterForeground
import eu.kanade.presentation.theme.LocalNyanimeStyle
import eu.kanade.presentation.theme.NyanimeWordmark
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.R
import tachiyomi.domain.discovery.SourceHomeGroup
import androidx.compose.ui.res.stringResource as androidStringResource

@Composable
fun DiscoveryHomeHeader(
    selectedHome: String?,
    onSelect: (String?) -> Unit,
    onBack: (() -> Unit)?,
    onSearch: (() -> Unit)?,
    onRefresh: () -> Unit,
    homes: List<SourceHomeGroup>,
    logo: SourceHomeLogo? = null,
    artworkRefreshKey: Int = 0,
    onUpdates: (() -> Unit)? = null,
    hasUpdates: Boolean = false,
    categoryOrder: String = "",
    onPrioritizeCategory: (HomeCategory) -> Unit = {},
) {
    if (!LocalNyanimeStyle.current) {
        return eu.kanade.presentation.discovery.legacy.DiscoveryHomeHeader(
            selectedHome,
            onSelect,
            onBack,
            onSearch,
            onRefresh,
            homes,
            logo,
            artworkRefreshKey,
            onUpdates,
            hasUpdates,
        )
    }
    val host = LocalDiscoveryHeaderHost.current
    val continuity = LocalHomeContinuity.current
    if (host != null) SideEffect { host.update(logo, artworkRefreshKey, hasUpdates, onUpdates) }
    Surface(
        modifier = if (continuity == null) Modifier.posterForeground(zIndex = 3f) else Modifier,
        color = if (continuity != null) Color.Transparent else MaterialTheme.colorScheme.background,
    ) {
        Column(if (host == null) Modifier.statusBarsPadding() else Modifier) {
            if (host == null) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = 54.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onBack != null) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, androidStringResource(R.string.home_back))
                            }
                        }
                        SourceHomeWordmark(
                            logo,
                            Modifier.weight(1f).padding(start = 4.dp),
                            refreshKey = artworkRefreshKey,
                        )
                        HomeHeaderActions(onUpdates, hasUpdates)
                    }
                }
            }
            HomeContentSwitch(selectedHome, homes, categoryOrder, onSelect, onPrioritizeCategory)
        }
    }
}

@Composable
private fun HomeHeaderActions(onUpdates: (() -> Unit)?, hasUpdates: Boolean) {
    val motion = appMotionEnabled()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Crossfade(
                targetState = onUpdates != null && hasUpdates,
                animationSpec = tween(if (motion) 160 else 0),
                label = "homeUpdatesAction",
            ) { visible ->
                if (visible) {
                    IconButton(onClick = { onUpdates?.invoke() }) {
                        Icon(Icons.Outlined.Adjust, contentDescription = androidStringResource(R.string.home_updates))
                    }
                }
            }
        }
        eu.kanade.tachiyomi.ui.watch.WatchTogetherButton()
    }
}

/** Availability and selection remain owned by the generic extension Home contract. */
@Composable
private fun HomeContentSwitch(
    selectedHome: String?,
    homes: List<SourceHomeGroup>,
    savedOrder: String,
    onSelect: (String?) -> Unit,
    onPrioritizeCategory: (HomeCategory) -> Unit,
) {
    val motion = modernMotionEnabled()
    val choices = HomeCategories.choices(homes, savedOrder)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val labelStyle = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val pageWidth = (maxWidth - 32.dp).coerceAtLeast(1.dp)
        val widths = choices.map { category ->
            (
                with(density) {
                    textMeasurer.measure(AnnotatedString(category.title), style = labelStyle).size.width.toDp()
                } +
                    24.dp
                )
                .coerceIn(64.dp, 220.dp)
                .coerceAtMost(pageWidth)
        }
        val pages = categoryPages(widths.map { it.value }, pageWidth.value, 12f)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .selectableGroup().padding(horizontal = 16.dp),
        ) {
            pages.forEach { page ->
                Column(Modifier.width(pageWidth), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    page.forEach { row ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        ) {
                            row.forEach { index ->
                                val category = choices[index]
                                key(category.orderKey) {
                                    val bringIntoView = remember { BringIntoViewRequester() }
                                    val isSelected = selectedHome == category.id
                                    val indicatorWidth by animateDpAsState(
                                        if (isSelected) 32.dp else 0.dp,
                                        tween(if (motion) 220 else 0),
                                        label = "homeIndicator",
                                    )
                                    val labelColor by animateColorAsState(
                                        if (isSelected) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        tween(if (motion) 180 else 0),
                                        label = "homeLabel",
                                    )
                                    LaunchedEffect(isSelected, pageWidth) {
                                        if (isSelected) bringIntoView.bringIntoView()
                                    }
                                    Column(
                                        Modifier.width(widths[index]).bringIntoViewRequester(bringIntoView)
                                            .combinedClickable(
                                                onClick = { onSelect(category.id) },
                                                onLongClick = {
                                                    if (choices.firstOrNull() != category) {
                                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        onPrioritizeCategory(category)
                                                    }
                                                },
                                                onLongClickLabel = androidStringResource(
                                                    R.string.home_prioritize,
                                                    category.title,
                                                ),
                                            )
                                            .semantics {
                                                role = Role.Tab
                                                selected = isSelected
                                                contentDescription = "Home ${category.title}"
                                            },
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Box(Modifier.heightIn(min = 36.dp), contentAlignment = Alignment.Center) {
                                            Text(
                                                category.title,
                                                color = labelColor,
                                                style = labelStyle,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Box(
                                            Modifier.width(indicatorWidth).height(3.dp)
                                                .background(MaterialTheme.colorScheme.primary),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@PreviewLightDark
@Preview(widthDp = 320, fontScale = 1.5f)
@Composable
private fun NyanimeHomeHeaderPreview() {
    TachiyomiPreviewTheme {
        DiscoveryHomeHeader(null, {}, null, {}, {}, homes = listOf(SourceHomeGroup("cartoons", "Cartoni", emptyList())))
    }
}

package eu.kanade.presentation.discovery.legacy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Adjust
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.discovery.SourceHomeArtwork
import eu.kanade.presentation.discovery.SourceHomeLogo
import eu.kanade.presentation.discovery.SourceHomeWordmark
import eu.kanade.presentation.discovery.sourceHomeLogoEnabled
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import tachiyomi.domain.discovery.SourceHomeGroup

@OptIn(ExperimentalMaterial3Api::class)
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
) {
    val motion = appMotionEnabled()
    val branded = sourceHomeLogoEnabled() && logo != null
    Column {
        TopAppBar(
            title = {
                if (branded) {
                    SourceHomeWordmark(logo, Modifier.fillMaxWidth(), refreshKey = artworkRefreshKey)
                } else if (homes.isNotEmpty()) {
                    HomeContentSwitch(selectedHome, homes, onSelect)
                } else {
                    Text("Home", style = MaterialTheme.typography.headlineMedium)
                }
            },
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Indietro") }
                }
            },
            actions = {
                eu.kanade.tachiyomi.ui.watch.WatchTogetherButton()
                AnimatedVisibility(
                    visible = onUpdates != null && hasUpdates,
                    enter = if (motion) expandHorizontally(tween(220)) + fadeIn(tween(160)) else EnterTransition.None,
                    exit = if (motion) shrinkHorizontally(tween(180)) + fadeOut(tween(120)) else ExitTransition.None,
                ) {
                    IconButton(onClick = { onUpdates?.invoke() }, enabled = hasUpdates && onUpdates != null) {
                        Icon(
                            Icons.Outlined.Adjust,
                            contentDescription = "Vai alle tue novità",
                        )
                    }
                }
                eu.kanade.tachiyomi.ui.community.CommunityAvatarButton()
                if (onSearch != null) {
                    IconButton(onClick = onSearch) {
                        Icon(
                            Icons.Outlined.Search,
                            homes.firstOrNull { it.id == selectedHome }?.let { "Cerca ${it.title}" } ?: "Cerca anime",
                        )
                    }
                }
                IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, "Aggiorna Home") }
            },
        )
        if (branded) HomeContentSwitch(selectedHome, homes, onSelect)
    }
}

/** Compact, single-tap navigation. State remains owned by the Home, not this visual control. */
@Composable
private fun HomeContentSwitch(selectedHome: String?, homes: List<SourceHomeGroup>, onSelect: (String?) -> Unit) {
    val shape = RoundedCornerShape(50)
    val choices = (if (homes.any { it.primary }) emptyList() else listOf(null to "Anime")) +
        homes.sortedWith(compareByDescending<SourceHomeGroup> { it.primary }.thenBy { it.title }).map { home ->
            home.id to home.title
        }
    Row(
        Modifier
            .widthIn(max = 280.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(4.dp)
            .selectableGroup()
            .horizontalScroll(rememberScrollState()),
    ) {
        choices.forEach { (value, label) ->
            key(value) {
                val bringIntoView = remember { BringIntoViewRequester() }
                val selected = selectedHome == value
                LaunchedEffect(selected) {
                    if (selected) bringIntoView.bringIntoView()
                }
                val background by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    label = "Home selection background",
                )
                val foreground by animateColorAsState(
                    if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    label = "Home selection text",
                )
                Box(
                    Modifier
                        .widthIn(min = 80.dp, max = 200.dp)
                        .heightIn(min = 48.dp)
                        .bringIntoViewRequester(bringIntoView)
                        .clip(shape)
                        .background(background)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(value) })
                        .semantics { contentDescription = "Home $label" }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = foreground,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun AnimeHomeHeaderPreview() {
    TachiyomiPreviewTheme {
        DiscoveryHomeHeader(null, {}, null, {}, {}, homes = previewHomes())
    }
}

@PreviewLightDark
@Preview(widthDp = 320, fontScale = 1.5f)
@Composable
private fun CartoonsHomeHeaderPreview() {
    TachiyomiPreviewTheme {
        DiscoveryHomeHeader("1", {}, {}, {}, {}, homes = previewHomes())
    }
}

@PreviewLightDark
@Composable
private fun HomeWithoutExtensionsPreview() {
    TachiyomiPreviewTheme {
        DiscoveryHomeHeader(null, {}, null, {}, {}, homes = emptyList())
    }
}

private fun previewHomes() = listOf("Cartoni", "Film").mapIndexed { index, title ->
    SourceHomeGroup((index + 1).toString(), title, emptyList())
}

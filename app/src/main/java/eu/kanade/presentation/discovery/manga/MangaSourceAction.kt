package eu.kanade.presentation.discovery.manga

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.discovery.MangaHomeItem
import eu.kanade.tachiyomi.data.discovery.MangaHomeRegistry
import eu.kanade.tachiyomi.data.discovery.MangaHomeService
import eu.kanade.tachiyomi.ui.entries.manga.MangaScreen
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.discovery.SourceHomeListing
import tachiyomi.domain.entries.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MangaSourceAction(
    item: MangaHomeItem,
    modifier: Modifier = Modifier,
    discover: Boolean = false,
    compact: Boolean = false,
    onOpen: ((Manga) -> Unit)? = null,
    onExpandedChange: (Boolean) -> Unit = {},
) {
    // Design previews have no installed extension registry or navigator.
    if (LocalInspectionMode.current) return
    val registry = remember { Injekt.get<MangaHomeRegistry>() }
    val service = remember { Injekt.get<MangaHomeService>() }
    val preferences = remember { Injekt.get<UiPreferences>() }
    val listing by registry.observe().collectAsState(initial = SourceHomeListing())
    val revision by service.identityChanges.collectAsState()
    val preferred by preferences.preferredMangaHomeSource().changes().collectAsState(
        initial = preferences.preferredMangaHomeSource().get(),
    )
    val navigator = LocalNavigator.currentOrThrow
    var expanded by remember(item.manga.source, item.manga.url) { mutableStateOf(false) }
    DisposableEffect(expanded) {
        onExpandedChange(expanded)
        onDispose { if (expanded) onExpandedChange(false) }
    }
    var loading by remember(item.manga.source, item.manga.url) { mutableStateOf(false) }
    var retry by remember(item.manga.source, item.manga.url) { mutableStateOf(0) }
    var resolved by remember(item.manga.source, item.manga.url) { mutableStateOf(item) }
    var failed by remember(item.manga.source, item.manga.url) { mutableStateOf(false) }
    val current = remember(item, resolved, revision, listing) {
        service.withAlternatives(if (resolved.presentation?.catalogIds.isNullOrEmpty()) item else resolved)
    }
    val variants = (listOf(current.variant()) + current.sourceVariants)
        .filter { variant -> listing.homes.any { it.id == variant.manga.source } }
    val sourceName = listing.homes.firstOrNull { it.id == item.manga.source }?.sourceName.orEmpty()
    LaunchedEffect(item.manga.source, item.manga.url, listing.homes.map { it.revision }, expanded, discover, retry) {
        if (listing.homes.size < 2 ||
            listing.homes.none { it.id == item.manga.source } ||
            (!expanded && !discover)
        ) {
            return@LaunchedEffect
        }
        loading = true
        failed = false
        try {
            resolved = service.resolveAlternatives(item)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }
    if (listing.homes.size < 2 || listing.homes.none { it.id == item.manga.source }) return
    val label = if (variants.size > 1) "${variants.size} fonti" else sourceName
    if (compact) {
        Surface(
            onClick = { expanded = true },
            modifier = modifier.heightIn(min = 32.dp),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.SwapHoriz, null, Modifier.size(16.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    } else {
        AssistChip(
            onClick = { expanded = true },
            modifier = modifier,
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Outlined.SwapHoriz, contentDescription = null, Modifier.size(18.dp)) },
        )
    }
    if (expanded) {
        ModalBottomSheet(onDismissRequest = { expanded = false }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
                Text(
                    "Scegli la fonte",
                    Modifier.padding(horizontal = 24.dp),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    item.manga.title,
                    Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (loading) {
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    )
                } else {
                    Spacer(Modifier.height(4.dp))
                }
                variants.forEach { variant ->
                    val source = listing.homes.first { it.id == variant.manga.source }
                    ListItem(
                        modifier = Modifier.clickable {
                            expanded = false
                            if (variant.manga.id != item.manga.id) {
                                onOpen?.invoke(variant.manga)
                                    ?: navigator.replace(MangaScreen(variant.manga.id, fromSource = true))
                            }
                        },
                        headlineContent = { Text(source.sourceName) },
                        supportingContent = {
                            Text(
                                if (preferred ==
                                    variant.manga.source
                                ) {
                                    "Fonte predefinita"
                                } else if (variant.manga.id ==
                                    item.manga.id
                                ) {
                                    "In uso"
                                } else {
                                    variant.sourceTitle
                                },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingContent = {
                            if (variant.manga.id ==
                                item.manga.id
                            ) {
                                Icon(Icons.Outlined.CheckCircle, contentDescription = "In uso")
                            }
                        },
                        trailingContent = {
                            IconButton(onClick = { preferences.preferredMangaHomeSource().set(variant.manga.source) }) {
                                Icon(
                                    if (preferred ==
                                        variant.manga.source
                                    ) {
                                        Icons.Outlined.Star
                                    } else {
                                        Icons.Outlined.StarBorder
                                    },
                                    contentDescription = "Usa ${source.sourceName} di default",
                                )
                            }
                        },
                    )
                }
                if (!loading && (variants.size < 2 || failed)) {
                    Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Nessun’altra fonte verificata.",
                            Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = { retry++ }) { Text("Riprova") }
                    }
                }
            }
        }
    }
}

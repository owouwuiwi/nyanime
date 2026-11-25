package eu.kanade.presentation.more.settings.screen.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.extension.UnifiedExtensionCatalogue.LinkedCatalogue
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.screen.browse.ExtensionCatalogueScreenModel.LinkedCataloguePreview
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.home.HomeScreen
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.components.material.Scaffold

class ExtensionCatalogueScreen(private val url: String) : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val model = rememberScreenModel { ExtensionCatalogueScreenModel(url) }
        val state by model.state.collectAsState()
        Scaffold(topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(R.string.extensions_catalogue_add),
                navigateUp = navigator::pop,
                scrollBehavior = scrollBehavior,
            )
        }) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            ) {
                Icon(
                    if (state is ExtensionCatalogueScreenModel.State.Done) {
                        Icons.Outlined.CheckCircle
                    } else {
                        Icons.Outlined.Extension
                    },
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                when (val current = state) {
                    ExtensionCatalogueScreenModel.State.Loading -> {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.catalogue_preview_checking))
                    }
                    is ExtensionCatalogueScreenModel.State.Failed -> {
                        val error = when {
                            current.driveFolder -> R.string.catalogue_drive_folder_error
                            current.driveFile -> R.string.catalogue_drive_file_error
                            else -> R.string.extensions_catalogue_error
                        }
                        Text(stringResource(error), textAlign = TextAlign.Center)
                        TextButton(onClick = model::load) { Text(stringResource(R.string.extensions_retry)) }
                    }
                    is ExtensionCatalogueScreenModel.State.Preview -> {
                        Text(
                            current.catalogue.name,
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                        )
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                val animeCount = current.catalogue.count("anime")
                                val mangaCount = current.catalogue.count("manga")
                                val newsCount = current.catalogue.count("news")
                                val bridgeCount = current.catalogue.bridgeCount
                                if (animeCount + mangaCount + newsCount > 0 ||
                                    bridgeCount == 0 && current.linkedCatalogues.isEmpty()
                                ) {
                                    Text(
                                        stringResource(R.string.extensions_catalogue_counts, animeCount, mangaCount, newsCount),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                                if (bridgeCount > 0) {
                                    Text(
                                        stringResource(R.string.catalogue_bridge_count, bridgeCount),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                                Text(
                                    url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (current.linkedCatalogues.isNotEmpty()) {
                            LinkedCataloguesPreview(current.linkedCatalogues)
                            if (!current.linksVerified) {
                                Text(
                                    stringResource(R.string.catalogue_links_incomplete),
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = TextAlign.Center,
                                )
                                TextButton(onClick = model::load) { Text(stringResource(R.string.extensions_retry)) }
                            }
                        }
                        Text(stringResource(R.string.extensions_catalogue_consent), textAlign = TextAlign.Center)
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.package_catalogue_trust),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    stringResource(
                                        if (current.linkedCatalogues.isEmpty()) R.string.package_catalogue_trust_hint
                                        else R.string.catalogue_primary_trust_hint,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Switch(
                                current.trustExtensions,
                                model::trustExtensions,
                                enabled = current.canAdd,
                            )
                        }
                        if (current.previous.isNotEmpty()) {
                            Text(
                                stringResource(R.string.extensions_catalogue_replaces, current.previous.joinToString()),
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                            )
                        }
                        if (current.failed) {
                            Text(
                                stringResource(R.string.extensions_catalogue_error),
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Button(
                            onClick = model::add,
                            enabled = current.canAdd,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (current.processing) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Text(stringResource(R.string.extensions_catalogue_add))
                            }
                        }
                    }
                    is ExtensionCatalogueScreenModel.State.Done -> {
                        Text(current.name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        Text(
                            stringResource(
                                if (current.includesLinks) R.string.catalogue_links_done
                                else R.string.extensions_catalogue_done,
                            ),
                            textAlign = TextAlign.Center,
                        )
                        Button(onClick = {
                            scope.launch {
                                navigator.popUntilRoot()
                                HomeScreen.openTab(HomeScreen.Tab.Browse())
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.catalogue_open_store)) }
                        TextButton(onClick = {
                            navigator.pop()
                        }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_close)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkedCataloguesPreview(catalogues: List<LinkedCataloguePreview>) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainer,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.catalogue_links_title), style = MaterialTheme.typography.titleMedium)
            catalogues.forEachIndexed { index, catalogue ->
                if (index > 0) HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    catalogue.name?.let {
                        Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    val type = when (catalogue.reference.type) {
                        LinkedCatalogue.Type.ANIME -> R.string.store_anime
                        LinkedCatalogue.Type.MANGA -> R.string.store_manga
                    }
                    val status = when (catalogue) {
                        is LinkedCataloguePreview.Verified -> R.string.catalogue_link_verified
                        is LinkedCataloguePreview.Mismatch -> R.string.catalogue_link_identity_mismatch
                        is LinkedCataloguePreview.Unavailable -> R.string.catalogue_link_unavailable
                    }
                    Text(
                        listOf(stringResource(type), stringResource(status)).joinToString(" · "),
                        color = if (catalogue is LinkedCataloguePreview.Verified) colors.onSurfaceVariant else colors.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        catalogue.reference.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

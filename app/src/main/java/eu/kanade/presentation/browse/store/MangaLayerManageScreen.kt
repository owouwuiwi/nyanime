package eu.kanade.presentation.browse.store

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.layer.MangaLayerRegistry
import eu.kanade.tachiyomi.data.layer.MangaLayerStatus
import tachiyomi.presentation.core.components.material.Scaffold
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal class MangaLayerManageScreen(
    private val layerPackage: String? = null,
    private val basePackage: String? = null,
) : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val registry = remember { Injekt.get<MangaLayerRegistry>() }
        val choices by registry.choices.collectAsState()
        fun target(choice: eu.kanade.tachiyomi.data.layer.MangaLayerChoice) =
            listOf(choice.medium, choice.basePackage, choice.sourceId.toString(), choice.capability)
        val targets = choices.filter { it.packageName == layerPackage }.map(::target).toSet()
        val shown = if (basePackage == null) choices.filter { target(it) in targets } else choices.filter { it.basePackage == basePackage }
        Scaffold(
            topBar = { behavior ->
                AppBar(title = stringResource(R.string.store_manga_layer), navigateUp = navigator::pop, scrollBehavior = behavior)
            },
        ) { padding ->
            if (shown.isEmpty()) {
                Text(stringResource(R.string.source_layers_empty), Modifier.padding(padding).padding(16.dp))
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                    items(shown, key = { "${it.packageName}:${target(it).joinToString(":")}" }) { choice ->
                        val capability = when (choice.capability) {
                            "home" -> R.string.store_manga_layer_home
                            "catalog" -> R.string.store_manga_layer_catalog
                            "update" -> R.string.store_manga_layer_update
                            "identity" -> R.string.store_manga_layer_identity
                            "reader" -> R.string.store_manga_layer_reader
                            "player" -> R.string.source_layers_playback
                            "feed" -> R.string.source_layers_feed
                            "article" -> R.string.source_layers_article
                            "search" -> R.string.source_layers_search
                            else -> R.string.source_layers_guide
                        }
                        ListItem(
                            headlineContent = { Text(stringResource(capability)) },
                            overlineContent = { Text(choice.name) },
                            supportingContent = {
                                Column {
                                    Text(choice.sourceName)
                                    Text(
                                        stringResource(
                                            when (choice.status) {
                                                MangaLayerStatus.ACTIVE -> R.string.source_layers_active
                                                MangaLayerStatus.NOT_SELECTED -> R.string.source_layers_unselected
                                                MangaLayerStatus.INCOMPATIBLE -> R.string.source_layers_incompatible
                                                MangaLayerStatus.DEPENDENCY_MISSING -> R.string.source_layers_dependency
                                                MangaLayerStatus.FAILED -> R.string.source_layers_failed
                                            },
                                        ),
                                    )
                                }
                            },
                            trailingContent = {
                                if (choice.status == MangaLayerStatus.ACTIVE) {
                                    TextButton(onClick = { registry.useOriginal(choice) }) {
                                        Text(stringResource(R.string.source_layers_original))
                                    }
                                } else if (choice.status != MangaLayerStatus.INCOMPATIBLE) {
                                    TextButton(onClick = { registry.select(choice) }) {
                                        Text(stringResource(R.string.source_layers_choose))
                                    }
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

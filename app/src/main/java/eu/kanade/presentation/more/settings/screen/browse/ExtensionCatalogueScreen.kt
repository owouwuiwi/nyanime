package eu.kanade.presentation.more.settings.screen.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
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
                    ExtensionCatalogueScreenModel.State.Loading -> CircularProgressIndicator()
                    ExtensionCatalogueScreenModel.State.Failed -> {
                        Text(stringResource(R.string.extensions_catalogue_error), textAlign = TextAlign.Center)
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
                                Text(
                                    stringResource(
                                        R.string.extensions_catalogue_counts,
                                        current.catalogue.count("anime"),
                                        current.catalogue.count("manga"),
                                        current.catalogue.count("news"),
                                    ),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(stringResource(R.string.extensions_catalogue_consent), textAlign = TextAlign.Center)
                        androidx.compose.foundation.layout.Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.package_catalogue_trust),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    stringResource(R.string.package_catalogue_trust_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            androidx.compose.material3.Switch(
                                current.trustExtensions,
                                model::trustExtensions,
                                enabled = !current.processing,
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
                            enabled = !current.processing,
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
                        Text(stringResource(R.string.extensions_catalogue_done), textAlign = TextAlign.Center)
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

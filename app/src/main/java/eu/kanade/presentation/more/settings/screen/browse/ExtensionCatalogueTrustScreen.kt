package eu.kanade.presentation.more.settings.screen.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.extension.ExtensionCatalogueTrust
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.domain.extension.anime.repository.AnimeExtensionStoreRepository
import mihon.domain.extensionrepo.manga.repository.MangaExtensionRepoRepository
import tachiyomi.presentation.core.components.material.Scaffold
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ExtensionCatalogueTrustScreen : Screen() {
    private data class Catalogue(val url: String, val name: String, val key: String)

    @Composable override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val trust = remember { ExtensionCatalogueTrust(Injekt.get()) }
        val approvals by trust.changes.collectAsState(trust.get())
        val catalogues by produceState<List<Catalogue>?>(null) {
            value = withContext(Dispatchers.IO) {
                (
                    Injekt.get<AnimeExtensionStoreRepository>().getAll().map {
                        Catalogue(it.indexUrl, it.name, it.signingKey.lowercase())
                    } +
                        Injekt.get<MangaExtensionRepoRepository>().getAll().map {
                            Catalogue(it.baseUrl, it.name, it.signingKeyFingerprint.lowercase())
                        }
                    ).groupBy { it.url }.values.mapNotNull { copies ->
                    copies.first().takeIf { copies.map { it.key }.distinct().size == 1 }
                }.sortedBy { it.name }
            }
        }
        Scaffold(topBar = { behavior ->
            AppBar(
                title = stringResource(R.string.package_catalogue_trust),
                navigateUp = navigator::pop,
                scrollBehavior = behavior,
            )
        }) { padding ->
            LazyColumn(
                Modifier.padding(padding).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        stringResource(R.string.package_catalogue_trust_hint),
                        Modifier.padding(vertical = 16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                items(catalogues.orEmpty(), key = Catalogue::url) { catalogue ->
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(catalogue.name, style = MaterialTheme.typography.titleMedium)
                                Text(catalogue.url, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    catalogue.key.chunked(8).joinToString(" "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                approvals.any { it.url == catalogue.url && it.signer == catalogue.key },
                                { trust.set(catalogue.url, catalogue.key, it) },
                                enabled = catalogue.key.matches(Regex("[a-f0-9]{64}")),
                            )
                        }
                    }
                }
                if (catalogues?.isEmpty() == true) item { Text(stringResource(R.string.store_empty_catalogue)) }
            }
        }
    }
}

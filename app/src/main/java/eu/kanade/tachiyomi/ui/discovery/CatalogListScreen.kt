package eu.kanade.tachiyomi.ui.discovery

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.discovery.LoadNotice
import eu.kanade.presentation.discovery.PosterCard
import eu.kanade.presentation.discovery.airingLabel
import eu.kanade.presentation.discovery.displayTitle
import eu.kanade.presentation.privacy.privacyRegion
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.privacy.PrivacyArea
import tachiyomi.domain.discovery.CatalogFeed

class CatalogListScreen(private val feed: CatalogFeed) : Screen() {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val model = rememberScreenModel { CatalogListScreenModel(feed) }
        val state by model.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        var query by rememberSaveable { mutableStateOf("") }
        LaunchedEffect(query) { if (feed == CatalogFeed.SEARCH) model.search(query) }
        Scaffold(modifier = Modifier.privacyRegion(PrivacyArea.SEARCH, enabled = feed == CatalogFeed.SEARCH), topBar = {
            TopAppBar(title = { Text(feed.displayTitle()) }, navigationIcon = {
                IconButton(onClick = { navigator.pop() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Indietro") }
            })
        }) { padding ->
            LazyVerticalGrid(
                GridCells.Adaptive(148.dp),
                Modifier.padding(padding),
                contentPadding = PaddingValues(12.dp),
            ) {
                if (feed == CatalogFeed.SEARCH) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        OutlinedTextField(query, {
                            query = it
                        }, Modifier.fillMaxWidth().padding(bottom = 12.dp), label = {
                            Text("Titolo dell’anime")
                        }, singleLine = true)
                    }
                }
                item(span = {
                    GridItemSpan(maxLineSpan)
                }) { LoadNotice(state.loading, state.error, state.stale) { model.load(reset = true) } }
                state.notice?.let { notice ->
                    item(span = { GridItemSpan(maxLineSpan) }) { Text(notice, Modifier.padding(12.dp)) }
                }
                items(state.items, key = { "${it.id.provider}:${it.id.value}:${it.airingAt}" }) { anime ->
                    PosterCard(
                        anime.title,
                        anime.cover,
                        if (feed ==
                            CatalogFeed.WEEK
                        ) {
                            airingLabel(anime)
                        } else {
                            anime.score?.let { "★ $it/100" }
                        },
                        { navigator.push(CatalogDetailScreen(anime.id.value, anime.id.provider)) },
                        Modifier.padding(6.dp),
                    )
                }
                if (!state.loading && state.items.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            if (feed == CatalogFeed.SEARCH &&
                                query.isBlank()
                            ) {
                                "Cerca nel catalogo anime"
                            } else {
                                "Nessun risultato"
                            },
                            Modifier.padding(16.dp),
                        )
                    }
                }
                if (state.hasNext && state.items.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        TextButton(onClick = { model.load() }, enabled = !state.loading) { Text("Carica altri") }
                    }
                }
            }
        }
    }
}

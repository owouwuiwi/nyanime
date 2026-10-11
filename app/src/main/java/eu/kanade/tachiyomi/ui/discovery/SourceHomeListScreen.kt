package eu.kanade.tachiyomi.ui.discovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.discovery.LoadNotice
import eu.kanade.presentation.discovery.SourceHomeActiveFilters
import eu.kanade.presentation.discovery.SourceHomeChoiceDialog
import eu.kanade.presentation.discovery.SourceHomeFilterSheet
import eu.kanade.presentation.discovery.SourceHomePosterCard
import eu.kanade.presentation.discovery.SourceHomeRankingCard
import eu.kanade.presentation.privacy.privacyRegion
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.discovery.SourceHomeSourceChoice
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.privacy.PrivacyArea
import kotlinx.coroutines.launch
import tachiyomi.domain.discovery.SourceHomeRequest
import tachiyomi.domain.discovery.homeItemKey
import tachiyomi.domain.discovery.homePresentation

class SourceHomeListScreen(
    private val homeKey: String,
    private val sectionId: String,
    private val title: String,
    private val date: String? = null,
) : Screen() {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        var selectedDate by rememberSaveable { mutableStateOf(date) }
        val model = rememberScreenModel { SourceHomeListScreenModel(homeKey, sectionId, date = date) }
        val state by model.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        val context = androidx.compose.ui.platform.LocalContext.current
        var query by rememberSaveable { mutableStateOf("") }
        var showFilters by rememberSaveable { mutableStateOf(false) }
        val isCatalogue = sectionId == SourceHomeRequest.SEARCH || sectionId.startsWith("category:")
        val availability = DiscoveryHomeAvailability.from(state.access)
        val leaveSourcePage = availability.shouldLeaveSourcePage(navigator.lastItem == this)
        LaunchedEffect(leaveSourcePage) {
            if (leaveSourcePage && !navigator.pop()) navigator.replace(DiscoveryTab)
        }
        val source = state.access.group
        if (availability.loading || source == null) {
            // A restored route or an extension removal must not expose source-specific labels or cached cards.
            Scaffold(topBar = { TopAppBar(title = { Text("Home") }) }) { padding ->
                Box(Modifier.padding(padding)) { LoadNotice(availability.loading, null) }
            }
            return
        }
        val scope = rememberCoroutineScope()
        var surpriseLoading by remember { mutableStateOf(false) }
        val surprise: (Boolean) -> Unit = { episode ->
            if (!surpriseLoading) {
                scope.launch {
                    surpriseLoading = true
                    try {
                        val anime = model.surprise(episode)
                        if (anime != null) {
                            val id = SourceHomeSourceChoice.preferredAnimeId(context, anime)
                            navigator.push(
                                AnimeScreen(
                                    id,
                                    true,
                                    SourceHomeSourceChoice.episodeTarget(anime, id).takeIf {
                                        episode
                                    },
                                ),
                            )
                        } else {
                            android.widget.Toast.makeText(
                                context,
                                "Nessun risultato: riprova",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    } finally {
                        surpriseLoading = false
                    }
                }
            }
        }
        val ranking = source.sections.firstOrNull { it.id == sectionId }?.layout == "ranking"
        val playEpisode = source.sections.firstOrNull { it.id == sectionId }?.layout != "featured"
        var pendingChoice by rememberSaveable { mutableStateOf<Long?>(null) }
        val chosenCard = state.items.firstOrNull { it.id == pendingChoice }
        chosenCard?.let { anime ->
            SourceHomeChoiceDialog(
                anime,
                source,
                SourceHomeSourceChoice.preferredAnimeId(context, anime),
                { pendingChoice = null },
            ) { id, remember ->
                if (remember) SourceHomeSourceChoice.remember(context, anime, id)
                pendingChoice = null
                navigator.push(
                    AnimeScreen(
                        id,
                        true,
                        SourceHomeSourceChoice.episodeTarget(anime, id).takeIf {
                            playEpisode
                        },
                    ),
                )
            }
        }
        LaunchedEffect(query) { if (isCatalogue) model.search(query) }
        LaunchedEffect(selectedDate) { model.selectDate(selectedDate) }
        if (showFilters) {
            SourceHomeFilterSheet(
                source.browseFilters,
                state.filters,
                onDismiss = { showFilters = false },
                onApply = model::applyFilters,
            )
        }
        Scaffold(modifier = Modifier.privacyRegion(PrivacyArea.SEARCH, enabled = isCatalogue), topBar = {
            Column {
                TopAppBar(title = {
                    Text(if (isCatalogue) "Esplora ${source.title}" else state.title ?: title)
                }, navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Indietro")
                    }
                })
                if (isCatalogue) {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(
                            query,
                            { query = it },
                            Modifier.fillMaxWidth(),
                            placeholder = { Text("Titolo, parola chiave…") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { model.search(query, immediate = true) }),
                            shape = RoundedCornerShape(24.dp),
                            leadingIcon = { Icon(Icons.Outlined.Search, null) },
                            trailingIcon = {
                                if (query.isNotEmpty()) {
                                    IconButton(onClick = { query = "" }) {
                                        Icon(Icons.Outlined.Close, "Cancella ricerca")
                                    }
                                }
                            },
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (state.filters.isEmpty()) {
                                    "Tutto il catalogo"
                                } else {
                                    "${state.filters.size} filtri attivi"
                                },
                                modifier = Modifier.weight(1f),
                            )
                            if (source.browseFilters.isNotEmpty()) {
                                FilledTonalButton(onClick = { showFilters = true }) {
                                    Icon(Icons.Outlined.Tune, null)
                                    Text("Filtri", Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                        eu.kanade.presentation.search.SearchAssistanceBar(
                            state.assistance,
                            onSuggestion = {
                                query = it
                                model.search(it, immediate = true)
                            },
                            onExact = model::searchExactly,
                        )
                        SourceHomeActiveFilters(state.filters) {
                            model.applyFilters(state.filters - it)
                        }
                    }
                }
            }
        }) { padding ->
            LazyVerticalGrid(
                GridCells.Adaptive(148.dp),
                Modifier.padding(padding),
                contentPadding = PaddingValues(12.dp),
            ) {
                if (source.sections.firstOrNull { it.id == sectionId }?.supportsDate == true) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        eu.kanade.presentation.discovery.SourceHomeDateSelector(selectedDate) {
                            selectedDate = it
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    val error = when {
                        state.access.offline -> "Modalità solo download: il catalogo della fonte è disattivato"
                        else -> state.error
                    }
                    LoadNotice(state.loading || state.access.loading, error, state.stale) {
                        model.load(reset = state.items.isEmpty())
                    }
                }
                if (sectionId == SourceHomeRequest.SEARCH &&
                    query.isBlank() &&
                    state.filters.isEmpty() &&
                    !state.access.offline &&
                    (source.hasRandom || source.hasRandomEpisode)
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        RandomDiscoverySection(
                            hasTitle = source.hasRandom,
                            hasEpisode = source.hasRandomEpisode,
                            loading = surpriseLoading,
                            onTitle = { surprise(false) },
                            onEpisode = { surprise(true) },
                        )
                    }
                }
                items(state.items, key = {
                    it.homeItemKey
                }, span = { if (ranking) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { anime ->
                    if (ranking) {
                        SourceHomeRankingCard(
                            anime,
                            state.items.indexOf(anime) + 1,
                            source.sourceLabel(anime.source),
                            {
                                val id = SourceHomeSourceChoice.preferredAnimeId(context, anime)
                                navigator.push(
                                    AnimeScreen(
                                        id,
                                        true,
                                        SourceHomeSourceChoice.episodeTarget(anime, id).takeIf {
                                            playEpisode
                                        },
                                    ),
                                )
                            },
                            { pendingChoice = anime.id },
                        )
                    } else {
                        SourceHomePosterCard(
                            anime,
                            source.sourceLabel(anime.source),
                            {
                                val id = SourceHomeSourceChoice.preferredAnimeId(context, anime)
                                navigator.push(
                                    AnimeScreen(
                                        id,
                                        true,
                                        SourceHomeSourceChoice.episodeTarget(anime, id).takeIf {
                                            playEpisode
                                        },
                                    ),
                                )
                            },
                            Modifier.padding(6.dp),
                            onSources = { pendingChoice = anime.id },
                        )
                    }
                }
                if (!state.loading &&
                    state.items.isEmpty() &&
                    state.access.group != null &&
                    !state.access.offline &&
                    state.error == null
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            if (isCatalogue && query.isBlank() && state.filters.isEmpty()) {
                                "Nessun titolo disponibile"
                            } else {
                                "Nessun risultato"
                            },
                            Modifier.padding(16.dp),
                        )
                    }
                }
                if (state.hasNext && state.items.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LaunchedEffect(state.items.size, state.error) {
                            if (!state.loading && state.error == null) model.load()
                        }
                        TextButton(onClick = { model.load() }, enabled = !state.loading) {
                            Text("Carica altri")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RandomDiscoverySection(
    hasTitle: Boolean,
    hasEpisode: Boolean,
    loading: Boolean,
    onTitle: () -> Unit,
    onEpisode: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 6.dp, vertical = 12.dp)) {
        Text(
            if (loading) "Sto scegliendo per te…" else "Lascia scegliere a Nyanime",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "Una scoperta dal catalogo, quando non sai cosa guardare.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (hasTitle) {
                Surface(
                    onClick = onTitle,
                    enabled = !loading,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Text("Sorprendimi", style = MaterialTheme.typography.titleSmall)
                        Text("Un titolo da scoprire", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (hasEpisode) {
                Surface(
                    onClick = onEpisode,
                    enabled = !loading,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Icon(Icons.Outlined.PlayCircleOutline, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Text("Episodio casuale", style = MaterialTheme.typography.titleSmall)
                        Text("Inizia subito a guardare", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

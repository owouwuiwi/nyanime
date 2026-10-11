package eu.kanade.tachiyomi.ui.discovery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.discovery.CatalogDetailsContent
import eu.kanade.presentation.discovery.LoadNotice
import eu.kanade.presentation.discovery.PosterCard
import eu.kanade.presentation.discovery.SectionHeader
import eu.kanade.presentation.motion.PosterDetailsScreen
import eu.kanade.presentation.motion.PosterLoadingBody
import eu.kanade.presentation.motion.posterDetailPreview
import eu.kanade.presentation.theme.LocalNyanimeStyle
import eu.kanade.presentation.theme.nyanimeBrandColors
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.browse.anime.source.browse.BrowseAnimeSourceScreen
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import tachiyomi.domain.discovery.CatalogId
import tachiyomi.domain.entries.anime.model.asAnimeCover
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class CatalogDetailScreen(private val catalogId: Long, private val provider: String = "anilist") :
    Screen(), PosterDetailsScreen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val model = rememberScreenModel { CatalogDetailScreenModel(CatalogId(provider, catalogId)) }
        val state by model.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        var query by rememberSaveable { mutableStateOf("") }
        var manualVersion by rememberSaveable { mutableStateOf(false) }
        val anime = state.details.data
        val loadingPoster = posterDetailPreview() != null && anime == null
        LaunchedEffect(state.query) { if (query.isBlank()) query = state.query }
        LaunchedEffect(model) {
            if (manualVersion) model.search(query)
            model.events.collect { event ->
                when (event) {
                    is CatalogDetailScreenModel.Event.OpenAnime -> navigator.push(AnimeScreen(event.id, true))
                    is CatalogDetailScreenModel.Event.Play -> context.playDiscoveryEpisode(event.episode)
                }
            }
        }
        val closeResolver = {
            manualVersion = false
            model.closeResolver()
        }
        BackHandler(state.resolver, closeResolver)
        Scaffold(topBar = {
            TopAppBar(
                title = { Text(if (state.resolver) "Scegli versione" else "Scheda anime") },
                navigationIcon = {
                    IconButton(onClick = { if (state.resolver) closeResolver() else navigator.pop() }) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            "Indietro",
                        )
                    }
                },

            )
        }) { padding ->
            LazyColumn(
                Modifier.padding(padding),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Box(Modifier.heightIn(min = if (LocalNyanimeStyle.current) 4.dp else 0.dp)) {
                        LoadNotice(
                            state.details.loading || state.busy,
                            state.details.error,
                            state.details.stale,
                        ) { model.load(true) }
                    }
                }
                state.message?.let { message -> item { Text(message, Modifier.padding(16.dp)) } }
                if (state.resolver) {
                    item {
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            Text("Seleziona la stagione e la lingua corrette. La scelta verrà ricordata.")
                            OutlinedTextField(
                                query,
                                { query = it },
                                if (LocalNyanimeStyle.current) {
                                    Modifier.fillMaxWidth()
                                } else {
                                    Modifier.padding(
                                        horizontal = 16.dp,
                                    )
                                },
                                label = { Text("Titolo da cercare nelle fonti") },
                                singleLine = true,
                            )
                            TextButton(onClick = { model.search(query) }) { Text("Cerca nelle fonti") }
                        }
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp)) {
                            items(
                                (listOfNotNull(anime?.title) + anime?.alternateTitles.orEmpty()).distinct().take(5),
                            ) { title ->
                                TextButton(onClick = {
                                    query = title
                                    model.search(title)
                                }) { Text(title) }
                            }
                        }
                        LoadNotice(state.searching, null)
                    }
                    items(state.results, key = { it.source.id }) { result ->
                        Column {
                            SectionHeader("${result.source.name} (${result.source.language.uppercase()})") {
                                navigator.push(BrowseAnimeSourceScreen(result.source.id, query))
                            }
                            LoadNotice(false, result.error) { model.search(query) }
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(result.items, key = { it.id }) { candidate ->
                                    PosterCard(
                                        candidate.title,
                                        candidate.asAnimeCover(),
                                        result.source.language.uppercase(),
                                        {
                                            manualVersion = false
                                            model.choose(candidate)
                                        },
                                    )
                                }
                            }
                            if (result.items.isEmpty() && result.error == null) {
                                Text("Nessun risultato", Modifier.padding(horizontal = 16.dp))
                            }
                        }
                    }
                    if (!state.searching && state.results.isEmpty()) {
                        item {
                            Text(
                                "Nessun risultato. Controlla le estensioni e le lingue abilitate.",
                                Modifier.padding(16.dp),
                            )
                        }
                    }
                } else if (loadingPoster) {
                    item { PosterLoadingBody(catalog = true) }
                } else if (anime != null) {
                    item {
                        CatalogDetailsContent(anime, actions = {
                            Column(
                                Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (state.next != null) {
                                    Button(
                                        onClick = { model.open(CatalogDetailScreenModel.Action.RESUME) },
                                        enabled = !state.busy,
                                        modifier = Modifier.fillMaxWidth().then(
                                            if (LocalNyanimeStyle.current) Modifier.heightIn(min = 48.dp) else Modifier,
                                        ),
                                        shape = if (LocalNyanimeStyle.current) {
                                            RoundedCornerShape(
                                                4.dp,
                                            )
                                        } else {
                                            ButtonDefaults.shape
                                        },
                                        colors = if (LocalNyanimeStyle.current) {
                                            ButtonDefaults.buttonColors(
                                                containerColor = nyanimeBrandColors.actionBackground,
                                                contentColor = nyanimeBrandColors.actionText,
                                            )
                                        } else {
                                            ButtonDefaults.buttonColors()
                                        },
                                    ) { Text("Riprendi · ${state.next?.name}") }
                                }
                                Button(
                                    onClick = { model.open() },
                                    enabled = !state.busy,
                                    modifier = Modifier.fillMaxWidth().then(
                                        if (LocalNyanimeStyle.current) Modifier.heightIn(min = 48.dp) else Modifier,
                                    ),
                                    shape = if (LocalNyanimeStyle.current) {
                                        RoundedCornerShape(
                                            4.dp,
                                        )
                                    } else {
                                        ButtonDefaults.shape
                                    },
                                    colors = if (LocalNyanimeStyle.current) {
                                        ButtonDefaults.buttonColors(
                                            containerColor = if (state.next == null) {
                                                nyanimeBrandColors.actionBackground
                                            } else {
                                                MaterialTheme.colorScheme.surfaceContainerHigh
                                            },
                                            contentColor = if (state.next == null) {
                                                nyanimeBrandColors.actionText
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                        )
                                    } else {
                                        ButtonDefaults.buttonColors()
                                    },
                                ) { Text("Apri episodi") }
                                if (state.linked?.favorite !=
                                    true
                                ) {
                                    TextButton(
                                        onClick = { model.open(CatalogDetailScreenModel.Action.ADD) },
                                        enabled = !state.busy,
                                    ) { Text("Aggiungi alla libreria") }
                                }
                                TextButton(
                                    onClick = {
                                        manualVersion = true
                                        model.open(chooseVersion = true)
                                    },
                                    enabled = !state.busy,
                                ) {
                                    Text(if (state.linked == null) "Cerca nelle fonti" else "Cambia versione")
                                }
                            }
                        }) { navigator.push(CatalogDetailScreen(it.value, it.provider)) }
                    }
                }
            }
        }
    }
}

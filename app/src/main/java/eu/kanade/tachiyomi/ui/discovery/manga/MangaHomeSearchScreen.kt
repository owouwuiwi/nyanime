package eu.kanade.tachiyomi.ui.discovery.manga

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.privacy.privacyRegion
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.discovery.MangaGenreLabels
import eu.kanade.tachiyomi.data.discovery.MangaHomeItem
import eu.kanade.tachiyomi.data.discovery.MangaHomeMerge
import eu.kanade.tachiyomi.data.discovery.MangaHomePage
import eu.kanade.tachiyomi.data.discovery.MangaHomeRegistry
import eu.kanade.tachiyomi.data.discovery.MangaHomeService
import eu.kanade.tachiyomi.ui.entries.manga.MangaScreen
import eu.kanade.tachiyomi.ui.privacy.PrivacyArea
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.discovery.SourceHomeListing
import tachiyomi.domain.discovery.SourceHomeRequest
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.search.ExtensionSearchAdapter
import tachiyomi.domain.search.SearchMedium
import tachiyomi.domain.search.SearchPage
import tachiyomi.domain.search.TitleSearch
import tachiyomi.domain.search.runtimeSearchTitle
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Search only among the installed manga Home providers currently allowed by the language toggle. */
class MangaHomeSearchScreen(
    private val initialGenre: String? = null,
    private val homeId: String? = null,
) : Screen() {
    @Composable
    override fun Content() {
        val registry = remember { Injekt.get<MangaHomeRegistry>() }
        val service = remember { Injekt.get<MangaHomeService>() }
        val uiPreferences = remember { Injekt.get<UiPreferences>() }
        val listing by registry.observe().collectAsState(initial = SourceHomeListing())
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val titleSearch = remember { Injekt.get<TitleSearch>() }
        var exact by remember { mutableStateOf(false) }
        var submitted by remember { mutableIntStateOf(0) }
        var query by rememberSaveable { mutableStateOf("") }
        var selectedSource by rememberSaveable { mutableStateOf<String?>(null) }
        var selectedGenre by rememberSaveable { mutableStateOf(initialGenre) }
        var page by rememberSaveable { mutableIntStateOf(1) }
        var items by remember { mutableStateOf<List<MangaHomeItem>>(emptyList()) }
        val identityRevision by service.identityChanges.collectAsState()
        val preferred by uiPreferences.preferredMangaHomeSource().changes().collectAsState(
            initial = uiPreferences.preferredMangaHomeSource().get(),
        )
        LaunchedEffect(identityRevision, preferred) {
            items = service.mergeKnown(MangaHomePage(items, false), preferred.takeIf { it != 0L }).items
        }
        var hasMore by remember { mutableStateOf(false) }
        var loading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        val homes = listing.homes.filter { it.search != null && (homeId == null || it.homeId == homeId) }
        val genres = MangaGenreLabels.distinct(homes.flatMap { it.categories }.map { it.title })
        val selectedHomes = homes.filter { selectedSource == null || it.key == selectedSource }
        val eligible = selectedHomes.mapNotNull { home ->
            val section = selectedGenre?.let { genre ->
                home.categories.firstOrNull { MangaGenreLabels.key(it.title) == MangaGenreLabels.key(genre) }
            }
            if (selectedGenre != null && section == null) null else home to section
        }
        val revision = eligible.joinToString("|") { it.first.key + ":" + it.first.revision }

        val session = remember(query, selectedSource, selectedGenre, revision, exact, submitted) {
            titleSearch.session(query, SearchMedium.MANGA, exact)
        }
        val assistance by session.assistance.collectAsState()

        LaunchedEffect(query, selectedSource, selectedGenre, page, revision, exact, submitted) {
            loading = true
            error = null
            if (page == 1) {
                hasMore = false
            }
            if (query.isNotBlank() && submitted == 0) delay(350)
            val previousItems = if (page > 1) items else emptyList()
            val pagesBySource = mutableMapOf<String, MangaHomePage>()
            val failures = mutableListOf<Throwable>()
            val preferredSource = uiPreferences.preferredMangaHomeSource().get().takeIf { it != 0L }
            coroutineScope {
                val completed = Channel<Pair<String, Result<MangaHomePage>>>(eligible.size.coerceAtLeast(1))
                eligible.forEach { (home, section) ->
                    launch {
                        val result = try {
                            Result.success(
                                if (query.isBlank()) {
                                    service.fetch(
                                        home.key,
                                        SourceHomeRequest(section?.id ?: SourceHomeRequest.SEARCH, page, query),
                                    )
                                } else {
                                    withContext(Dispatchers.IO) {
                                        val result = session.search(
                                            mangaHomeAdapter(
                                                service,
                                                home.key,
                                                section?.id ?: SourceHomeRequest.SEARCH,
                                            ),
                                            page,
                                        )
                                        MangaHomePage(result.items, result.hasNextPage)
                                    }
                                },
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            Result.failure(failure)
                        }
                        completed.send(home.key to result)
                    }
                }
                repeat(eligible.size) {
                    val (sourceKey, result) = completed.receive()
                    result.onSuccess { pagesBySource[sourceKey] = it }
                        .onFailure { failures += it }
                    val available = eligible.mapNotNull { (home, _) -> pagesBySource[home.key] }
                    if (available.isNotEmpty()) {
                        val merged = MangaHomeMerge.merge(available, preferredSource)
                        items =
                            service.mergeKnown(
                                merged.copy(items = previousItems + merged.items),
                                preferredSource,
                            ).items
                        hasMore = merged.hasNextPage
                    }
                }
            }
            val pages = eligible.mapNotNull { (home, _) -> pagesBySource[home.key] }
            error = if (pages.isEmpty() && eligible.isNotEmpty()) {
                failures.firstNotNullOfOrNull { it.message } ?: "Ricerca non riuscita"
            } else {
                null
            }
            loading = false
            if (pages.isEmpty() && page == 1) items = emptyList()
            if (pages.isNotEmpty()) {
                val enriched = coroutineScope {
                    pages.map { sourcePage ->
                        async {
                            sourcePage.copy(
                                items = sourcePage.items.map { item ->
                                    if (item.presentation?.catalogIds?.isNotEmpty() == true) {
                                        item
                                    } else {
                                        try {
                                            service.enrichIdentity(item)
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            item
                                        }
                                    }
                                },
                            )
                        }
                    }.awaitAll()
                }
                val identityMerged = MangaHomeMerge.merge(enriched, preferredSource)
                items = service.mergeKnown(
                    MangaHomeMerge.merge(
                        listOf(MangaHomePage(previousItems, false), identityMerged),
                        preferredSource,
                    ),
                    preferredSource,
                ).items
            }
        }

        Surface(
            Modifier.fillMaxSize().privacyRegion(PrivacyArea.SEARCH),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = { navigator.pop() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Indietro")
                    }
                    Text(
                        homes.firstOrNull()?.title?.let { stringResource(R.string.reading_home_explore_category, it) }
                            ?: stringResource(R.string.reading_home_explore),
                        Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        exact = false
                        submitted = 0
                        page = 1
                    },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    placeholder = { Text("Titolo o parola chiave") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        submitted++
                        page = 1
                    }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                eu.kanade.presentation.search.SearchAssistanceBar(
                    assistance,
                    onSuggestion = {
                        query = it
                        exact = false
                        submitted++
                        page = 1
                    },
                    onExact = {
                        exact = !exact
                        submitted++
                        page = 1
                    },
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(selected = selectedSource == null, onClick = {
                            selectedSource = null
                            page = 1
                        }, label = { Text("Tutte le fonti") })
                    }
                    items(homes, key = { it.key }) { home ->
                        FilterChip(
                            selected = selectedSource == home.key,
                            onClick = {
                                selectedSource = home.key
                                page = 1
                            },
                            label = { Text("${home.sourceName} · ${home.language.uppercase()}") },
                        )
                    }
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(selected = selectedGenre == null, onClick = {
                            selectedGenre = null
                            page = 1
                        }, label = { Text("Tutti i generi") })
                    }
                    items(genres) { genre ->
                        FilterChip(selected = selectedGenre == genre, onClick = {
                            selectedGenre = genre
                            page = 1
                        }, label = { Text(genre) })
                    }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    if (homes.isEmpty() && !listing.loading) {
                        item { Text("Nessuna fonte manga disponibile con la lingua scelta.") }
                    } else if (eligible.isEmpty()) {
                        item { Text("Nessuna fonte supporta questo filtro. Scegli un altro genere.") }
                    } else if (items.isEmpty() && !loading) {
                        item { Text(error ?: "Nessun manga trovato.") }
                    }
                    items(items, key = { it.key }) { item ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                AsyncImage(
                                    model = ImageRequest.Builder(
                                        context,
                                    ).data(item.manga.copy(favorite = false)).crossfade(180).build(),
                                    contentDescription = item.manga.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.width(86.dp).height(129.dp).clickable {
                                        navigator.push(MangaScreen(item.manga.id, fromSource = true))
                                    },
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item.manga.title,
                                        Modifier.clickable {
                                            navigator.push(MangaScreen(item.manga.id, fromSource = true))
                                        },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    eu.kanade.presentation.discovery.manga.MangaSourceAction(item, onOpen = { manga ->
                                        navigator.push(MangaScreen(manga.id, fromSource = true))
                                    })
                                }
                            }
                        }
                    }
                    if (hasMore && items.isNotEmpty()) {
                        item { TextButton(onClick = { page++ }, enabled = !loading) { Text("Mostra altri") } }
                    }
                }
            }
        }
    }
}

private fun mangaHomeAdapter(service: MangaHomeService, homeKey: String, section: String) =
    object : ExtensionSearchAdapter<MangaHomeItem> {
        override val key = homeKey
        override fun identity(item: MangaHomeItem) = item.manga.source.toString() + ":" + item.manga.url
        override fun title(item: MangaHomeItem) = runtimeSearchTitle(
            item.manga.source,
            item.manga.url,
            item.sourceTitle,
            SearchMedium.MANGA,
        )
        override suspend fun fetch(page: Int, query: String): SearchPage<MangaHomeItem> {
            val result = service.fetch(homeKey, SourceHomeRequest(section, page, query))
            return SearchPage(result.items, result.hasNextPage)
        }
    }

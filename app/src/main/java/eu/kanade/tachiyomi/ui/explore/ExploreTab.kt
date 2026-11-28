package eu.kanade.tachiyomi.ui.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.TabOptions
import coil3.compose.AsyncImage
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.discovery.LocalHomeContentActive
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.rin.RinRecord
import eu.kanade.tachiyomi.data.rin.RinRepository
import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.discovery.CatalogDetailScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.home.LocalFloatingNavigationInset
import eu.kanade.tachiyomi.ui.home.LocalSelectHomeTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import logcat.LogPriority
import logcat.logcat
import tachiyomi.domain.discovery.CatalogAnime
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.random.Random

private const val TRAILER_SWIPE_THRESHOLD = 0.12f

data object ExploreTab : Tab {
    override val options: TabOptions
        @Composable get() = TabOptions(
            2u,
            stringResource(R.string.explore_navigation),
            rememberVectorPainter(Icons.Outlined.Explore),
        )

    @Composable
    override fun Content() {
        val context = LocalContext.current.applicationContext
        val repository = remember(context) { RinRepository.get(context) }
        val installed by repository.installed.collectAsState()
        val selectTab = LocalSelectHomeTab.current
        val navigator = LocalNavigator.currentOrThrow
        val homeActive = LocalHomeContentActive.current && navigator.lastItem == HomeScreen
        val openStore = { BrowseTab.showAddons(); selectTab(BrowseTab) }
        LaunchedEffect(repository) {
            repository.reloadInstalled()
            eu.kanade.tachiyomi.data.extensionstore.ExtensionStoreRepository.get(context).refresh()
        }
        val providers = installed.filter {
            it.enabled && it.trusted && it.manifest.medium == "addon" &&
                it.manifest.addon?.isExploreFeed == true && RinRepository.compatible(it.manifest, it.format)
        }
        var selectedProvider by rememberSaveable { mutableStateOf<String?>(null) }
        val record = providers.firstOrNull { it.manifest.packageName == selectedProvider } ?: providers.firstOrNull()
        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(bottom = LocalFloatingNavigationInset.current),
        ) {
            if (record == null) {
                ExploreHeader { }
                ExploreNotice(R.string.explore_install_hint, R.string.store_title, openStore)
            } else {
                key(record.sha256) {
                    ExploreFeed(
                        record, providers, homeActive, openStore,
                        onProviderSelected = { selectedProvider = it.manifest.packageName },
                    ) { clip, episodes ->
                        val anime = clip.anime
                        navigator.push(
                            CatalogDetailScreen(
                                anime.id.value,
                                anime.id.provider,
                                exploreJson.encodeToString(CatalogAnime.serializer(), anime),
                                openEpisodes = episodes,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExploreHeader(actions: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp).heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.explore_navigation),
            Modifier.weight(1f),
            style = MaterialTheme.typography.headlineSmall,
        )
        actions()
    }
}

@Composable
private fun ExploreFeed(
    record: RinRecord,
    providers: List<RinRecord>,
    homeActive: Boolean,
    openStore: () -> Unit,
    onProviderSelected: (RinRecord) -> Unit,
    open: (ExploreClip, Boolean) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val provider = remember(record.sha256) { ExploreProvider(context, record) }
    val preferences = remember { Injekt.get<BasePreferences>() }
    val privateMode by preferences.incognitoMode().changes().collectAsState(preferences.incognitoMode().get())
    val downloadsOnly by preferences.downloadedOnly().changes().collectAsState(preferences.downloadedOnly().get())
    var resumed by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        resumed = true
        onPauseOrDispose { resumed = false }
    }
    var saved by remember(provider, privateMode) { mutableStateOf(provider.saved()) }
    fun update(next: ExploreSaved) {
        saved = next
        if (!privateMode) provider.save(next)
    }
    val seed = rememberSaveable { Random.nextInt() }
    var page by remember { mutableStateOf(1) }
    var hasMore by remember { mutableStateOf(true) }
    var retry by remember { mutableStateOf(0) }
    var loadedPage by remember { mutableStateOf(0) }
    var emptyPages by remember { mutableStateOf(0) }
    var clips by remember { mutableStateOf(emptyList<ExploreClip>()) }
    var failed by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(emptySet<String>()) }
    var unsupported by remember { mutableStateOf(emptySet<String>()) }
    val cards = remember(clips, saved.hidden, unsupported) {
        clips.filterNot { it.key in saved.hidden || it.youtubeId in unsupported }
    }
    // Compose can keep a key callback from the previous layout while the feed changes.
    val pagerCards by rememberUpdatedState(cards)
    val pager = rememberPagerState { pagerCards.size }
    var showSaved by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var showLicense by remember { mutableStateOf(false) }
    var license by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(page, retry, homeActive, resumed, downloadsOnly) {
        if (!hasMore || page <= loadedPage || !homeActive || !resumed || downloadsOnly) return@LaunchedEffect
        failed = false
        try {
            val response = provider.load(page, seed, saved, clips)
            val next = (clips + response.items).distinctBy { it.key }
            loadedPage = page
            val containsVisibleClip = response.items.any { item -> item.key !in saved.hidden && clips.none { it.key == item.key } }
            emptyPages = if (containsVisibleClip) 0 else emptyPages + 1
            hasMore = response.hasMore && emptyPages < 3
            clips = next
            if (!containsVisibleClip && hasMore) page++
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logcat(LogPriority.WARN) { "Could not load Explore trailers: ${error.javaClass.simpleName}" }
            failed = true
        }
    }
    val current = cards.getOrNull(pager.settledPage)
    LaunchedEffect(current?.key, cards.size, showSaved, homeActive, resumed) {
        if (!showSaved && homeActive && resumed) {
            if (current != null && !privateMode) update(saved.rememberSeen(current.key))
            if ((cards.isEmpty() || pager.settledPage == cards.lastIndex) && hasMore && !failed && loadedPage == page) {
                page++
            }
        }
    }

    ExploreHeader {
        IconButton(onClick = { showSaved = !showSaved }) {
            Icon(
                if (showSaved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                stringResource(R.string.explore_watchlist),
            )
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Outlined.MoreVert, stringResource(R.string.label_more))
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(record.manifest.name) }, onClick = {
                    menu = false
                    showLicense = true
                    scope.launch {
                        try {
                            license = provider.license()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            license = null
                        }
                    }
                })
                providers.filterNot { it == record }.forEach { other ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.explore_switch_provider, other.manifest.name)) },
                        onClick = {
                            menu = false
                            onProviderSelected(other)
                        },
                    )
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.store_title)) }, onClick = {
                    menu = false
                    openStore()
                })
            }
        }
    }
    if (showLicense) {
        AlertDialog(
            onDismissRequest = { showLicense = false },
            title = { Text(record.manifest.name) },
            text = {
                Text(
                    listOfNotNull(record.manifest.addon?.sourceUrl, license).joinToString("\n\n"),
                    Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { showLicense = false }) { Text(stringResource(android.R.string.ok)) } },
        )
    }
    when {
        showSaved -> ExploreSavedList(saved, open, ::update)
        downloadsOnly -> ExploreNotice(R.string.explore_downloads_only)
        cards.isEmpty() && failed -> ExploreNotice(R.string.explore_load_failed, R.string.explore_retry) {
            retry++
        }
        cards.isEmpty() && hasMore -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        cards.isEmpty() -> ExploreNotice(R.string.explore_no_trailers)
        else -> BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            VerticalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
                flingBehavior = PagerDefaults.flingBehavior(pager, snapPositionalThreshold = TRAILER_SWIPE_THRESHOLD),
                key = { index -> pagerCards.getOrNull(index)?.key ?: index },
            ) { index ->
                val visibleCards = pagerCards
                val clip = visibleCards.getOrNull(index) ?: return@VerticalPager
                ExplorePageContent(
                    clip = clip,
                    stageWidth = maxWidth.coerceAtMost(900.dp),
                    viewportHeight = maxHeight,
                    play = homeActive && resumed && !showLicense &&
                        pager.settledPage == index && pager.currentPage == index && clip.youtubeId !in blocked,
                    failed = clip.youtubeId in blocked,
                    saved = saved,
                    open = { open(clip, it) },
                    unavailable = { unsupportedByPlayer ->
                        if (unsupportedByPlayer) unsupported = unsupported + clip.youtubeId
                        else blocked = blocked + clip.youtubeId
                    },
                    retryTrailer = { blocked = blocked - clip.youtubeId },
                    onUpdate = ::update,
                    loadMore = if (index == visibleCards.lastIndex && failed && hasMore) {
                        { retry++ }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun ExplorePageContent(
    clip: ExploreClip,
    stageWidth: Dp,
    viewportHeight: Dp,
    play: Boolean,
    failed: Boolean,
    saved: ExploreSaved,
    open: (Boolean) -> Unit,
    unavailable: (Boolean) -> Unit,
    retryTrailer: () -> Unit,
    onUpdate: (ExploreSaved) -> Unit,
    loadMore: (() -> Unit)?,
) {
    val uriHandler = LocalUriHandler.current
    val footerHeight = (viewportHeight * .35f).coerceAtLeast(256.dp).coerceAtMost(viewportHeight * .60f)
    val mediaHeight = viewportHeight - footerHeight
    val playerHeight = mediaHeight - ExploreTrailerControlsHeight
    val canEmbed = stageWidth >= 200.dp && playerHeight >= 200.dp
    Column(Modifier.widthIn(max = stageWidth).fillMaxSize()) {
        Box(
            Modifier.fillMaxWidth().height(mediaHeight)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = clip.anime.banner ?: clip.anime.cover,
                contentDescription = clip.anime.title,
                modifier = Modifier.fillMaxSize().clickable { open(false) },
                contentScale = ContentScale.Crop,
            )
            if (canEmbed) {
                key(clip.youtubeId, playerHeight) {
                    ExploreYoutubePlayer(clip.youtubeId, playerHeight, play, Modifier.fillMaxSize(), unavailable)
                }
            }
        }
        Row(
            Modifier.height(footerHeight).fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    clip.anime.title,
                    Modifier.fillMaxWidth().clickable { open(false) }.padding(vertical = 4.dp),
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val synopsis = remember(clip.anime.synopsis) {
                    clip.anime.synopsis?.let {
                        HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
                    }
                }
                if (!synopsis.isNullOrBlank()) {
                    Text(
                        synopsis,
                        Modifier.clickable { open(false) },
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                clip.metric?.let {
                    val count = java.text.NumberFormat.getIntegerInstance().format(it.value)
                    val label = when (it.kind) {
                        "ratings" -> stringResource(R.string.explore_metric_ratings, it.label)
                        "popularity" -> stringResource(R.string.explore_metric_popularity, it.label)
                        else -> it.label
                    }
                    Text("$label · $count", style = MaterialTheme.typography.labelMedium)
                }
                if (failed && canEmbed) {
                    TextButton(onClick = retryTrailer) { Text(stringResource(R.string.explore_retry)) }
                }
                if (failed || !canEmbed) {
                    TextButton(onClick = {
                        uriHandler.openUri("https://www.youtube.com/watch?v=${clip.youtubeId}")
                    }) { Text(stringResource(R.string.explore_open_youtube)) }
                }
                TextButton(onClick = { open(false) }) { Text(stringResource(R.string.explore_open_details)) }
                loadMore?.let {
                    TextButton(onClick = it) { Text(stringResource(R.string.explore_retry)) }
                }
            }
            ExploreActions(
                clip, saved, onUpdate,
                onEpisodes = { open(true) },
                modifier = Modifier.width(52.dp).verticalScroll(rememberScrollState()),
            )
        }
    }
}


@Composable
private fun ExploreActions(
    clip: ExploreClip,
    saved: ExploreSaved,
    onUpdate: (ExploreSaved) -> Unit,
    onEpisodes: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        val liked = saved.liked.any { it.key == clip.key }
        val favorite = saved.favorite.any { it.key == clip.key }
        val inWatchlist = saved.watchlist.any { it.key == clip.key }
        IconToggleButton(liked, onCheckedChange = {
            onUpdate(saved.copy(liked = saved.liked.toggle(clip)))
        }) {
            Icon(if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, stringResource(R.string.explore_like), tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
        IconButton(onClick = onEpisodes) {
            Icon(Icons.Outlined.PlayCircleOutline, stringResource(R.string.explore_episodes))
        }
        IconToggleButton(favorite, onCheckedChange = {
            onUpdate(saved.copy(favorite = saved.favorite.toggle(clip)))
        }) {
            Icon(if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder, stringResource(R.string.explore_favorites))
        }
        IconToggleButton(inWatchlist, onCheckedChange = {
            onUpdate(saved.copy(watchlist = saved.watchlist.toggle(clip)))
        }) {
            Icon(
                if (inWatchlist) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                stringResource(R.string.explore_watchlist),
            )
        }
        var more by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { more = true }) {
                Icon(Icons.Outlined.MoreVert, stringResource(R.string.label_more))
            }
            DropdownMenu(more, onDismissRequest = { more = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.explore_hide)) }, onClick = {
                    more = false
                    onUpdate(saved.copy(hidden = saved.hidden + clip.key))
                })
            }
        }
    }
}

@Composable
private fun ExploreSavedList(
    saved: ExploreSaved,
    open: (ExploreClip, Boolean) -> Unit,
    update: (ExploreSaved) -> Unit,
) {
    if (saved.watchlist.isEmpty() && saved.favorite.isEmpty()) {
        ExploreNotice(R.string.explore_saved_empty)
    } else {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            val sections = listOf(R.string.explore_watchlist to saved.watchlist, R.string.explore_favorites to saved.favorite)
            sections.forEach { (label, entries) ->
                if (entries.isNotEmpty()) {
                    item { Text(stringResource(label), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium) }
                    items(entries.asReversed(), key = { "$label:${it.key}" }) { clip ->
                        ListItem(
                            headlineContent = { Text(clip.anime.title) },
                            modifier = Modifier.clickable { open(clip, false) },
                            trailingContent = {
                                IconButton(onClick = {
                                    update(
                                        if (label == R.string.explore_watchlist) {
                                            saved.copy(watchlist = saved.watchlist.toggle(clip))
                                        } else {
                                            saved.copy(favorite = saved.favorite.toggle(clip))
                                        },
                                    )
                                }) {
                                    Icon(
                                        if (label == R.string.explore_watchlist) Icons.Filled.Bookmark else Icons.Filled.Star,
                                        stringResource(R.string.explore_remove_saved),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExploreNotice(message: Int, button: Int? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(message), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (button != null) Button(onClick = onAction) { Text(stringResource(button)) }
    }
}

private fun List<ExploreClip>.toggle(clip: ExploreClip): List<ExploreClip> =
    if (any { it.key == clip.key }) filterNot { it.key == clip.key } else this + clip

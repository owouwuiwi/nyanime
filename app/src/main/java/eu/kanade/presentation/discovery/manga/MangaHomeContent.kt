package eu.kanade.presentation.discovery.manga

import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.Extras
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.kanade.presentation.discovery.FadingAsyncImage
import eu.kanade.presentation.discovery.FeaturedCoverCarousel
import eu.kanade.presentation.discovery.FeaturedCoverSkeleton
import eu.kanade.presentation.discovery.HomeExploreAction
import eu.kanade.presentation.discovery.HomeGenreChip
import eu.kanade.presentation.discovery.HomeLoadingTransition
import eu.kanade.presentation.discovery.HomeMotion
import eu.kanade.presentation.discovery.HomePosterRowSkeleton
import eu.kanade.presentation.discovery.HomeResumeCard
import eu.kanade.presentation.discovery.HomeSelectionChip
import eu.kanade.presentation.discovery.HomeSkeleton
import eu.kanade.presentation.discovery.HomeUpdateCard
import eu.kanade.presentation.discovery.SectionHeader
import eu.kanade.presentation.discovery.featuredCarouselAutoplay
import eu.kanade.presentation.motion.PosterSource
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.presentation.motion.posterSource
import eu.kanade.presentation.motion.posterSourcePlaceholder
import eu.kanade.presentation.privacy.nsfwPrivacy
import eu.kanade.presentation.privacy.privacyRegion
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.coil.MangaCoverFetcher
import eu.kanade.tachiyomi.data.discovery.MangaGenreLabels
import eu.kanade.tachiyomi.data.discovery.MangaHomeChapter
import eu.kanade.tachiyomi.data.discovery.MangaHomeFeatured
import eu.kanade.tachiyomi.data.discovery.MangaHomeItem
import eu.kanade.tachiyomi.ui.discovery.manga.MangaHomeState
import eu.kanade.tachiyomi.ui.privacy.PrivacyArea
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import tachiyomi.domain.discovery.SourceHomeSection
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.entries.manga.model.asMangaCover
import tachiyomi.domain.history.manga.model.MangaHistoryWithRelations
import tachiyomi.domain.updates.manga.model.MangaUpdatesWithRelations
import androidx.compose.ui.res.stringResource as androidStringResource

@Composable
fun MangaHomeContent(
    state: MangaHomeState,
    onRefresh: () -> Unit,
    onSelectHome: (String) -> Unit,
    onSelectAll: () -> Unit,
    onPreferredSource: (Long) -> Unit,
    onManga: (Manga) -> Unit,
    onChapter: (MangaHomeItem, MangaHomeChapter) -> Unit,
    onResume: (MangaHistoryWithRelations) -> Unit,
    onArchive: (Map<String, String>) -> Unit,
    onExplore: () -> Unit,
    onGenre: (String) -> Unit,
    onMore: (SourceHomeSection) -> Unit,
    onRetry: (String) -> Unit,
    onUpdates: () -> Unit,
    onUpdate: (MangaUpdatesWithRelations) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val motionDuration = if (appMotionEnabled()) HomeMotion.CONTENT_MILLIS else 0
    val home = state.selected
    val sections = if (state.mixed) {
        state.homes.flatMap {
            it.sections
        }.distinctBy { it.id }
    } else {
        home?.sections.orEmpty()
    }
    val featuredSections = if (state.mixed) state.homes.flatMap { it.sections } else sections
    val featured = remember(featuredSections, state.rows) {
        MangaHomeFeatured.collect(featuredSections, state.rows)
    }
    val featuredItems = featured.items
    var sourceSheetOpen by remember { mutableStateOf(false) }
    val rotateFeatured =
        featuredCarouselAutoplay(listState, "hero") && !sourceSheetOpen && state.rows.values.none { it.loading }
    val awaitingFeatured = featured.loading
    val personalUpdates = state.updates
    var displayedSourceKey by rememberSaveable { mutableStateOf(home?.key) }
    LaunchedEffect(home?.key) {
        if (displayedSourceKey != home?.key) {
            listState.scrollToItem(0)
            displayedSourceKey = home?.key
        }
    }
    val refreshing = state.rows.values.any { it.loading }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewportWidth = maxWidth
        val columns = when {
            maxWidth >= 900.dp -> 3
            maxWidth >= 600.dp -> 2
            else -> 1
        }
        tachiyomi.presentation.core.components.material.PullRefresh(
            refreshing = refreshing,
            enabled = !state.offline,
            onRefresh = onRefresh,
            indicatorOnGestureOnly = true,
            indicatorPadding = PaddingValues(top = eu.kanade.presentation.discovery.LocalHomeContentInset.current),
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = eu.kanade.presentation.discovery.LocalHomeContentInset.current,
                    bottom =
                    24.dp + eu.kanade.tachiyomi.ui.home.LocalFloatingNavigationInset.current,
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (home != null && !state.offline) {
                    item("hero") {
                        HomeLoadingTransition(
                            loading = awaitingFeatured,
                            placeholder = { FeaturedCoverSkeleton() },
                        ) {
                            FeaturedCoverCarousel(
                                items = featuredItems,
                                itemKey = { it.key },
                                title = { it.manga.title },
                                metadata = {
                                    (it.presentation?.badges.orEmpty() + it.presentation?.details.orEmpty())
                                        .joinToString(" · ")
                                },
                                artworkData = { it.manga.copy(favorite = false) },
                                privacyModifier = { Modifier.nsfwPrivacy(it.manga) },
                                autoplay = rotateFeatured,
                                onOpen = { onManga(it.manga) },
                                actions = { item ->
                                    MangaSourceAction(item, compact = true, onOpen = onManga, onExpandedChange = {
                                        sourceSheetOpen =
                                            it
                                    })
                                },
                            ) { item, modifier, poster ->
                                Artwork(item.manga.copy(favorite = false), item.manga.title, modifier, poster, true)
                            }
                        }
                    }
                }
                if (!state.initializing && state.homes.isEmpty()) {
                    item("missing-source") {
                        Text(
                            "Installa e abilita un’estensione manga per esplorare la Home. " +
                                "La tua biblioteca resta disponibile qui sopra.",
                            Modifier.padding(horizontal = 20.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (state.homes.size > 1) {
                    item("sources") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            item(key = "all") {
                                HomeSelectionChip("Per te", state.mixed, onSelectAll)
                            }
                            items(state.homes, key = { it.key }) {
                                HomeSelectionChip(it.sourceName, !state.mixed && it.key == home?.key) {
                                    onSelectHome(it.key)
                                }
                            }
                        }
                    }
                }
                if (state.history.isNotEmpty()) {
                    item("continue") {
                        Column(Modifier.privacyRegion(PrivacyArea.RESUME)) {
                            SectionHeader(androidStringResource(R.string.home_continue_reading))
                            val pageWidth = viewportWidth - 40.dp
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(state.history.chunked(3), key = { it.first().mangaId }) { group ->
                                    Column(
                                        Modifier.width(pageWidth),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        group.forEach { history ->
                                            androidx.compose.runtime.key(history.mangaId) {
                                                val cloudResume = eu.kanade.tachiyomi.ui.cloud.rememberCloudResume(
                                                    "manga",
                                                    history.mangaId,
                                                    history.chapterId,
                                                )
                                                val resumeContext = androidx.compose.ui.platform.LocalContext.current
                                                HomeResumeCard(
                                                    title = history.title,
                                                    subtitle = (
                                                        if (history.chapterNumber >=
                                                            0
                                                        ) {
                                                            androidStringResource(
                                                                R.string.home_content_read_chapter,
                                                                formatChapterNumber(history.chapterNumber),
                                                            )
                                                        } else {
                                                            androidStringResource(R.string.home_resume_action)
                                                        }
                                                        ).let { subtitle ->
                                                        cloudResume.firstOrNull()?.let {
                                                            "$subtitle · ${it.deviceName}"
                                                        } ?: subtitle
                                                    },
                                                    onResume = { onResume(history) },
                                                    modifier = Modifier.nsfwPrivacy(history.coverData),
                                                    manga = true,
                                                    menu = cloudResume.takeIf { it.isNotEmpty() }?.let { positions ->
                                                        { close ->
                                                            eu.kanade.tachiyomi.ui.cloud.CloudResumeMenu(
                                                                positions,
                                                                manga = true,
                                                            ) { page ->
                                                                close()
                                                                resumeContext.startActivity(
                                                                    ReaderActivity.newIntent(
                                                                        resumeContext,
                                                                        history.mangaId,
                                                                        history.chapterId,
                                                                        page.coerceIn(
                                                                            0,
                                                                            Int.MAX_VALUE.toLong(),
                                                                        ).toInt(),
                                                                    ),
                                                                )
                                                            }
                                                        }
                                                    },
                                                ) { imageModifier ->
                                                    Artwork(history.coverData, history.title, imageModifier)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (personalUpdates.isNotEmpty()) {
                    item("personal-updates") {
                        Column {
                            SectionHeader(androidStringResource(R.string.home_your_updates), onUpdates)
                            val cardWidth = ((viewportWidth - 52.dp) / 2).coerceAtLeast(
                                150.dp * LocalDensity.current.fontScale.coerceAtMost(1.4f),
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(personalUpdates, key = { it.chapterId }) { update ->
                                    HomeUpdateCard(
                                        title = update.mangaTitle,
                                        subtitle = update.chapterName,
                                        onOpen = { onUpdate(update) },
                                        modifier = Modifier.width(cardWidth).nsfwPrivacy(update.coverData),
                                        manga = true,
                                    ) { imageModifier -> Artwork(update.coverData, update.mangaTitle, imageModifier) }
                                }
                            }
                        }
                    }
                }
                if (state.offline) {
                    item("offline") {
                        Text(
                            androidStringResource(R.string.home_downloaded_manga_hint),
                            Modifier.padding(20.dp),
                        )
                    }
                } else if (home != null) {
                    sections.forEach { section ->
                        val row = state.rows[section.id]
                        val rail = section.layout in listOf("chapters", "featured", "posters")
                        val awaitingContent = row?.page == null && row?.error == null && row?.loading != false
                        item("heading:" + section.id) {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SectionTitle(section.title, Modifier.weight(1f))
                                if (section.moreSelections != null || row?.page?.hasNextPage == true) {
                                    IconButton(onClick = { onMore(section) }, enabled = row?.loading != true) {
                                        Icon(
                                            Icons.AutoMirrored.Outlined.ArrowForward,
                                            contentDescription = "Mostra altri: " + section.title,
                                        )
                                    }
                                }
                            }
                        }
                        if (!rail && awaitingContent) {
                            item("loading:" + section.id) {
                                HomePosterRowSkeleton(
                                    modifier = Modifier.animateItem(
                                        fadeInSpec = tween(motionDuration),
                                        fadeOutSpec = tween(motionDuration),
                                        placementSpec = tween(motionDuration),
                                    ),
                                )
                            }
                        }
                        if (row?.error != null) {
                            item("error:" + section.id) {
                                Column(Modifier.padding(horizontal = 20.dp)) {
                                    Text(row.error, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    OutlinedButton(onClick = {
                                        onRetry(section.id)
                                    }) { Text(androidStringResource(R.string.room_retry)) }
                                }
                            }
                        }
                        val entries = row?.page?.items.orEmpty()
                        if (entries.isEmpty() && row?.page != null && row.error == null) {
                            item("empty:" + section.id) {
                                Text(
                                    androidStringResource(R.string.home_section_empty),
                                    Modifier.padding(horizontal = 20.dp),
                                )
                            }
                        }
                        if (rail) {
                            item("rail:" + section.id) {
                                HomeLoadingTransition(
                                    loading = awaitingContent,
                                    placeholder = { HomePosterRowSkeleton() },
                                ) {
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 20.dp),
                                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    ) {
                                        items(entries, key = { it.key }) { entry ->
                                            Column(
                                                Modifier.width(152.dp).nsfwPrivacy(entry.manga),
                                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                Artwork(
                                                    entry.manga.copy(favorite = false),
                                                    entry.manga.title,
                                                    Modifier.fillMaxWidth().clickable { onManga(entry.manga) },
                                                )
                                                Text(
                                                    entry.manga.title,
                                                    Modifier.clickable { onManga(entry.manga) },
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                    fontWeight = FontWeight.SemiBold,

                                                )
                                                MangaSourceSelector(entry, onManga)
                                                entry.presentation?.chapters.orEmpty().forEach { chapter ->
                                                    ChapterButton(
                                                        chapter,
                                                        state.opening == chapter.url,
                                                    ) {
                                                        onChapter(
                                                            entry,
                                                            chapter,
                                                        )
                                                    }
                                                }
                                                entry.presentation?.details.orEmpty().forEach {
                                                    Text(
                                                        it,
                                                        style = MaterialTheme.typography.bodySmall,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            items(
                                entries.chunked(columns),
                                key = { section.id + ":" + it.first().key },
                                contentType = { section.layout },
                            ) { group ->
                                Row(
                                    Modifier.animateItem(
                                        fadeInSpec = tween(motionDuration),
                                        fadeOutSpec = tween(motionDuration),
                                        placementSpec = tween(motionDuration),
                                    ).fillMaxWidth().padding(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    group.forEach { entry ->
                                        MangaUpdateCard(entry, state.opening, {
                                            onManga(entry.manga)
                                        }, state, onManga, onPreferredSource, Modifier.weight(1f)) {
                                            onChapter(entry, it)
                                        }
                                    }
                                    repeat(columns - group.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                        if (row?.page?.hasNextPage == true && entries.isNotEmpty()) {
                            item("next:" + section.id) {
                                TextButton(
                                    onClick = { onMore(section) },
                                    enabled = !row.loading,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        if (row.loading) {
                                            androidStringResource(
                                                R.string.home_loading,
                                            )
                                        } else {
                                            androidStringResource(R.string.home_more)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        modifier.padding(horizontal = 20.dp),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun MangaUpdateCard(
    item: MangaHomeItem,
    opening: String?,
    onManga: () -> Unit,
    state: MangaHomeState,
    onSourceManga: (Manga) -> Unit,
    onPreferredSource: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onChapter: (MangaHomeChapter) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f)),
        modifier = modifier.fillMaxWidth().nsfwPrivacy(item.manga),
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.width(100.dp)) {
                Artwork(
                    item.manga.copy(favorite = false),
                    item.manga.title,
                    Modifier.fillMaxWidth().clickable(onClick = onManga),
                )
                item.presentation?.rank?.let { rank ->
                    Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(bottomEnd = 10.dp)) {
                        Text(
                            rank.toString(),
                            Modifier.padding(
                                horizontal = 10.dp,
                                vertical = 6.dp,
                            ),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    item.manga.title,
                    Modifier.clickable(onClick = onManga),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                MangaSourceSelector(item, onSourceManga)
                item.presentation?.badges?.takeIf { it.isNotEmpty() }?.let {
                    Text(
                        it.joinToString(" · "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                item.presentation?.details.orEmpty().forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item.presentation?.chapters.orEmpty().forEach { chapter ->
                    ChapterButton(chapter, opening == chapter.url) { onChapter(chapter) }
                }
                if (item.presentation?.chapters.isNullOrEmpty()) {
                    TextButton(
                        onClick = onManga,
                        contentPadding = PaddingValues(horizontal = 0.dp),
                    ) { Text(androidStringResource(R.string.reading_home_open_title)) }
                }
            }
        }
    }
}

@Composable
private fun MangaSourceSelector(
    item: MangaHomeItem,
    onManga: (Manga) -> Unit,
) {
    MangaSourceAction(item, compact = true, onOpen = onManga)
}

@Composable
private fun ChapterButton(chapter: MangaHomeChapter, loading: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = !loading,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            1.dp,
            if (chapter.isNew) {
                MaterialTheme.colorScheme.primary.copy(alpha = .35f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f)
            },
        ),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Box {
            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    if (loading) androidStringResource(R.string.reading_home_opening) else chapter.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (chapter.isNew || !chapter.date.isNullOrBlank()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (chapter.isNew) {
                            Surface(
                                shape = RoundedCornerShape(5.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Text(
                                    androidStringResource(R.string.reading_home_new),
                                    Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                        chapter.date?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (loading) {
                LinearProgressIndicator(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp))
            }
        }
    }
}

@Composable
private fun Artwork(
    data: Any,
    title: String,
    modifier: Modifier = Modifier,
    poster: PosterSource? = null,
    fillFrame: Boolean = false,
) {
    val context = LocalContext.current
    val artwork = if (data is Manga) data.asMangaCover() else data
    var previous by remember { mutableStateOf<Painter?>(null) }
    var failed by remember(artwork) { mutableStateOf(false) }
    var retry by remember(artwork) { mutableIntStateOf(0) }
    val request = remember(artwork, retry, context) {
        ImageRequest.Builder(context).data(artwork)
            .apply {
                extras[MangaCoverFetcher.USE_CUSTOM_COVER_KEY] =
                    data is tachiyomi.domain.entries.manga.model.MangaCover
                extras[ArtworkRetryKey] = retry
            }
            .build()
    }
    Box(
        modifier.then(if (fillFrame) Modifier else Modifier.aspectRatio(2f / 3f))
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.MenuBook,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .25f),
            modifier = Modifier.align(Alignment.Center).size(32.dp),
        )
        FadingAsyncImage(
            model = request,
            imageLoader = SingletonImageLoader.get(context),
            contentDescription = title,
            previousPainter = previous ?: posterSourcePlaceholder(poster),
            reduceMotion = !appMotionEnabled(),
            onSuccess = {
                previous = it.painter
                poster?.painter = it.painter
                failed = false
            },
            onError = { failed = true },
            modifier = Modifier.fillMaxSize().posterSource(poster),
        )
        if (failed) {
            IconButton(onClick = {
                failed = false
                retry++
            }, modifier = Modifier.align(Alignment.Center)) {
                Icon(Icons.Outlined.Refresh, contentDescription = androidStringResource(R.string.home_reload_cover))
            }
        }
    }
}

private val ArtworkRetryKey = Extras.Key(0)

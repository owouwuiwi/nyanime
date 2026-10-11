package eu.kanade.tachiyomi.ui.news

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.news.NewsInterestMatch
import eu.kanade.tachiyomi.data.news.NewsMatchKind
import eu.kanade.tachiyomi.data.news.StoredNews
import eu.kanade.tachiyomi.ui.home.LocalFloatingNavigationInset
import kotlinx.coroutines.launch
import nyanime.news.api.NewsMedium
import tachiyomi.presentation.core.components.material.PullRefresh
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun NewsContent(model: NewsScreenModel, active: Boolean = true, search: Boolean = false) {
    val state by model.state.collectAsState()
    val extensions by model.repository.registry.state.collectAsState()
    val snapshot by model.repository.store.state.collectAsState()
    val navigator = LocalNavigator.currentOrThrow
    val context = LocalContext.current
    LaunchedEffect(Unit) { (context as? eu.kanade.tachiyomi.ui.main.MainActivity)?.ready = true }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val motion = appMotionEnabled()
    var visibleKeys by remember(state.generation, search) { mutableStateOf<List<String>?>(null) }
    val indexed = remember(state.items) { state.items.associateBy { it.key } }
    val items = if (search) state.items else visibleKeys?.mapNotNull(indexed::get) ?: state.items
    val newItems = !search && visibleKeys != null && state.items.any { it.key !in visibleKeys.orEmpty() }
    LaunchedEffect(state.items, state.generation, state.busy, state.personalizing) {
        if (visibleKeys == null && state.items.isNotEmpty()) {
            visibleKeys = state.items.map { it.key }
        }
    }
    LifecycleStartEffect(active, search) {
        if (active && !search) model.refresh()
        onStopOrDispose { model.pausePersonalization() }
    }
    PullRefresh(
        refreshing = state.busy,
        enabled = active,
        onRefresh = { model.refresh(true) },
        indicatorOnGestureOnly = true,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(
                    horizontal = 16.dp,
                    vertical = 6.dp,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (search) {
                    Text(
                        stringResource(R.string.news_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    listOf(
                        R.string.news_latest,
                        R.string.news_for_you,
                        R.string.news_saved,
                    ).forEachIndexed {
                            index,
                            title,
                        ->
                        TextButton(onClick = { model.tab(index) }, modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(title),
                                color = if (state.tab == index) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                fontWeight = if (state.tab == index) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
                if (!search) {
                    IconButton(onClick = { navigator.push(NewsSourcesScreen()) }) {
                        Icon(Icons.Outlined.Tune, stringResource(R.string.news_manage))
                    }
                }
            }
            if (!search) NewsFilterBar(model)
            Box(Modifier.fillMaxWidth().height(3.dp)) {
                if ((state.busy || state.tab == 1 && state.personalizing) && items.isNotEmpty()) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            AnimatedVisibility(
                newItems,
                enter = if (motion) ModernMotion.enter() else EnterTransition.None,
                exit = if (motion) ModernMotion.exit() else ExitTransition.None,
            ) {
                TextButton(onClick = {
                    visibleKeys = state.items.map { it.key }
                    scope.launch {
                        if (motion) list.animateScrollToItem(0) else list.scrollToItem(0)
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.news_show_new)) }
            }
            LazyColumn(
                state = list,
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = LocalFloatingNavigationInset.current + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.error) {
                    item {
                        Text(
                            stringResource(R.string.news_error),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                val limitedSearch = extensions.any {
                    it.source != null &&
                        !it.source.capabilities.search &&
                        (state.source == null || state.source == it.packageName)
                }
                if (search && limitedSearch) {
                    item {
                        Text(
                            stringResource(R.string.news_search_cached),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!search && state.tab == 1) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    stringResource(
                                        if (state.personalizing) {
                                            R.string.news_matching_progress
                                        } else {
                                            R.string.news_personal_note
                                        },
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                TextButton(onClick = { navigator.push(NewsInterestsScreen()) }) {
                                    Text(stringResource(R.string.news_personalize))
                                }
                            }
                        }
                    }
                }
                if ((state.busy || state.tab == 1 && state.personalizing) && items.isEmpty()) {
                    items(5) { NewsSkeleton() }
                } else if (items.isEmpty()) {
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 36.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                if (state.tab == 2) Icons.Outlined.BookmarkBorder else Icons.Outlined.Newspaper,
                                null,
                                Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val emptyLabel = if (extensions.none { it.source != null }) {
                                R.string.news_empty
                            } else {
                                R.string.news_no_results
                            }
                            Text(
                                stringResource(emptyLabel),
                                style = MaterialTheme.typography.titleLarge,
                            )
                            if (extensions.none { it.source != null }) {
                                Text(
                                    stringResource(R.string.news_empty_body),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                TextButton(onClick = { navigator.push(NewsSourcesScreen()) }) {
                                    Text(stringResource(R.string.news_manage))
                                }
                            }
                        }
                    }
                }
                items(items, key = { it.key }) { item ->
                    NewsCard(
                        item,
                        featured = item.key == items.firstOrNull()?.key && !search && state.tab == 0,
                        match = state.matches[item.key],
                    ) {
                        navigator.push(NewsReaderScreen(item.key))
                    }
                }
                if (state.canLoadMore && state.tab != 2) {
                    item {
                        TextButton(
                            onClick = model::more,
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.news_more)) }
                    }
                }
                if (items.isNotEmpty()) {
                    item {
                        Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.news_end), style = MaterialTheme.typography.labelLarge)
                            val enabledSources = extensions.filter { it.source != null }.map { it.packageName }.toSet()
                            snapshot.checks.filterKeys { it in enabledSources }.forEach { (
                                id,
                                check,
                            ),
                                ->
                                val source = extensions.firstOrNull { it.packageName == id }?.source?.name.orEmpty()
                                if (check.error) {
                                    Text(
                                        source + " · " + stringResource(R.string.news_error),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                } else if (check.checkedAt >
                                    0
                                ) {
                                    Text(
                                        source +
                                            " · " +
                                            stringResource(
                                                R.string.news_last_checked,
                                                newsDate(check.checkedAt),
                                            ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
fun NewsFilterBar(model: NewsScreenModel) {
    val state by model.state.collectAsState()
    val extensions by model.repository.registry.state.collectAsState()
    val sources = extensions.filter { it.source != null }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!state.searching) {
            item {
                FilterChip(
                    state.medium == null,
                    { model.filter(medium = null) },
                    label = { Text(stringResource(R.string.news_all)) },
                )
            }
        }
        val media = NewsMedium.entries.filter { medium ->
            !state.searching && sources.any { medium in requireNotNull(it.source).capabilities.media }
        }
        items(media) { medium ->
            FilterChip(
                state.medium == medium,
                { model.filter(medium = medium) },
                label = { Text(if (medium == NewsMedium.ANIME) "Anime" else "Manga") },
            )
        }
        if (sources.size > 1) {
            item {
                FilterChip(
                    state.source == null,
                    {
                        model.filter(
                            source = null,
                            category = null,
                        )
                    },
                    label = { Text(stringResource(R.string.news_sources)) },
                )
            }
            items(sources, key = { it.packageName }) { source ->
                FilterChip(
                    state.source == source.packageName,
                    {
                        model.filter(
                            source = source.packageName,
                            category = null,
                        )
                    },
                    label = { Text(requireNotNull(source.source).name) },
                )
            }
        }
        val chosen = sources.firstOrNull { it.packageName == state.source } ?: sources.singleOrNull()
        items(chosen?.source?.capabilities?.categories.orEmpty(), key = { "category:${it.id}" }) { category ->
            FilterChip(
                state.category == category.id,
                { model.filter(category = category.id.takeUnless { it == state.category }) },
                label = { Text(category.label) },
            )
        }
    }
}

@Composable
private fun NewsCard(item: StoredNews, featured: Boolean, match: NewsInterestMatch? = null, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = if (featured) 148.dp else 116.dp).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    item.article.publisher,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    item.article.title,
                    style = if (featured) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                val date = item.article.publishedAt?.let(::newsDate) ?: stringResource(R.string.news_date_unknown)
                val read = if (item.read) " · " + stringResource(R.string.news_read) else ""
                Text(
                    date + read,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                if (match != null && match.title.isNotBlank()) {
                    Text(
                        stringResource(
                            if (match.kind in setOf(NewsMatchKind.LOCAL_TITLE, NewsMatchKind.HEADLINE)) {
                                R.string.news_title_mention
                            } else {
                                R.string.news_about_title
                            },
                            match.title,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(
                Modifier.size(if (featured) 106.dp else 84.dp)
                    .clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Newspaper,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                )
                AsyncImage(item.article.imageUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
    }
}

@Composable
private fun NewsSkeleton() {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(3) {
                Spacer(
                    Modifier.fillMaxWidth(if (it == 2) 0.55f else 1f).height(16.dp)
                        .clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                )
            }
        }
        Spacer(
            Modifier.size(84.dp).clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
    }
}

internal fun newsDate(time: Long): String = DateTimeFormatter.ofLocalizedDateTime(
    FormatStyle.MEDIUM,
    FormatStyle.SHORT,
).format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))

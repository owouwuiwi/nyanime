package eu.kanade.tachiyomi.ui.tv

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.home.LocalFloatingNavigationInset
import kotlinx.coroutines.delay
import nyanime.tv.api.TvChannel
import nyanime.tv.api.TvChannelRef
import nyanime.tv.api.TvFacet
import nyanime.tv.api.TvProgramme
import nyanime.tv.api.TvRules
import tachiyomi.presentation.core.components.material.PullRefresh
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun TvHomeContent(
    model: TvHomeModel,
    active: Boolean,
    onPlay: (TvChannel) -> Unit,
    onShare: (TvChannel) -> Unit,
    onRoom: ((TvChannel) -> Unit)?,
) {
    val state by model.state.collectAsState()
    val channelList = rememberLazyListState()
    val guideList = rememberLazyListState()
    val timelineList = rememberLazyListState()
    val layout = if (!state.guide) {
        0
    } else if (state.personal.gridGuide) {
        2
    } else {
        1
    }
    val list = when (layout) {
        1 -> guideList
        2 -> timelineList
        else -> channelList
    }
    var menu by remember { mutableStateOf<TvChannel?>(null) }
    var programmeChannel by remember { mutableStateOf<TvChannel?>(null) }
    var facets by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val motion = appMotionEnabled()
    androidx.lifecycle.compose.LifecycleStartEffect(model, active) {
        model.active(active)
        onStopOrDispose { model.active(false) }
    }
    LaunchedEffect(active) {
        if (active) {
            while (true) {
                delay(30_000)
                now = System.currentTimeMillis()
                model.refreshVisibleGuide()
            }
        }
    }
    val channelKeys =
        remember(state.channels) { state.channels.associateBy { "channel:${it.ref.sourceId}:${it.ref.id}" } }
    val byReference = remember(state.channels, state.personalChannels) {
        state.personalChannels +
            state.channels.associateBy { it.ref }
    }
    LaunchedEffect(programmeChannel?.ref) { model.programmeFocus(programmeChannel?.ref) }
    DisposableEffect(model) { onDispose { model.programmeFocus(null) } }
    LaunchedEffect(list, channelKeys, state.personal.favourites) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.mapNotNull { item -> channelKeys[item.key]?.ref } }
            .collect { model.visible(it) }
    }
    LaunchedEffect(state.channels) { model.visible(state.channels.take(12).map { it.ref }) }
    LaunchedEffect(list, active) {
        if (active) {
            snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { index ->
                if (index >= list.layoutInfo.totalItemsCount - 5 &&
                    list.layoutInfo.totalItemsCount > 0
                ) {
                    model.loadMore()
                }
            }
        }
    }
    PullRefresh(refreshing = state.loading.isNotEmpty(), onRefresh = {
        model.refresh(true)
    }, enabled = true, indicatorOnGestureOnly = true) {
        Column(Modifier.fillMaxSize()) {
            if (state.personalFault) {
                Text(
                    stringResource(R.string.tv_settings_error),
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.extensionFailures.isNotEmpty()) {
                Text(
                    stringResource(R.string.tv_extension_error),
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                    listOf(R.string.tv_channels, R.string.tv_guide).forEachIndexed { index, label ->
                        SegmentedButton(selected = state.guide == (index == 1), onClick = {
                            model.guide(index == 1)
                        }, shape = SegmentedButtonDefaults.itemShape(index, 2), icon = {}) {
                            Text(stringResource(label), maxLines = 1)
                        }
                    }
                }
                IconButton(onClick = {
                    facets = true
                }) { Icon(Icons.Outlined.Tune, stringResource(R.string.tv_filters)) }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state.countries.firstOrNull {
                        it.id == state.selection.country
                    }?.label(Locale.getDefault().language)
                        ?: state.selection.country
                        ?: stringResource(R.string.tv_world),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                if (state.errors.isNotEmpty()) {
                    Text(
                        stringResource(
                            if (state.saved.isNotEmpty()) R.string.tv_catalogue_saved else R.string.tv_network_error,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.guide) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val guideSources = model.repository.registry.state.value.values.filter {
                        it.source.capabilities.guide
                    }.map { it.source.id }
                    val knownRange = guideSources.isNotEmpty() && guideSources.all { it in state.guideRanges }
                    val firstDay = state.guideRanges.values.minOfOrNull {
                        it.first
                    }?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
                    val lastDay = state.guideRanges.values.maxOfOrNull {
                        it.second
                    }?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
                    IconButton(
                        onClick = { model.day(state.day.minusDays(1)) },
                        enabled =
                        !knownRange || firstDay == null || state.day > firstDay,
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.tv_previous_day))
                    }
                    TextButton(onClick = { model.day(LocalDate.now()) }, modifier = Modifier.weight(1f)) {
                        Text(
                            if (state.day ==
                                LocalDate.now()
                            ) {
                                stringResource(R.string.tv_today)
                            } else {
                                state.day.format(DateTimeFormatter.ofPattern("EEE d MMM"))
                            },
                        )
                    }
                    IconButton(
                        onClick = { model.day(state.day.plusDays(1)) },
                        enabled =
                        !knownRange || lastDay == null || state.day < lastDay,
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, stringResource(R.string.tv_next_day))
                    }
                    IconButton(onClick = {
                        model.grid(!state.personal.gridGuide)
                    }) {
                        Icon(
                            if (state.personal.gridGuide) Icons.Outlined.ViewList else Icons.Outlined.GridView,
                            stringResource(R.string.tv_guide_layout),
                        )
                    }
                }
            }
            Crossfade(
                targetState = layout,
                animationSpec = tween(if (motion) ModernMotion.PAGE_MILLIS else 0),
                label = "TV view",
                modifier = Modifier.weight(1f),
            ) { view ->
                if (view == 2 && state.channels.isNotEmpty()) {
                    TvGuideTimeline(state, timelineList, now, onPlay)
                    return@Crossfade
                }
                val guide = view != 0
                LazyColumn(
                    state = if (guide) guideList else channelList,
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 10.dp,
                        bottom =
                        LocalFloatingNavigationInset.current + 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (!guide) {
                        val favourites = state.personal.favourites.mapNotNull { ref -> byReference[ref] }
                        val recent = state.personal.recent.filterNot {
                            it in state.personal.favourites
                        }.mapNotNull { ref -> byReference[ref] }
                        if (favourites.isNotEmpty()) {
                            item("favourites") {
                                TvShortcutRow(stringResource(R.string.tv_favourites), favourites, onPlay) {
                                    menu =
                                        it
                                }
                            }
                        }
                        if (recent.isNotEmpty()) {
                            item("recent") {
                                TvShortcutRow(stringResource(R.string.tv_recent), recent, onPlay) {
                                    menu =
                                        it
                                }
                            }
                        }
                        if (state.personalErrors.isNotEmpty() ||
                            state.personalUnavailable.isNotEmpty()
                        ) {
                            item("personal-status") {
                                Text(
                                    stringResource(
                                        if (state.personalErrors.isNotEmpty()) {
                                            R.string.tv_personal_refresh_error
                                        } else {
                                            R.string.tv_personal_unavailable
                                        },
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        val onAir = state.programmes.keys.mapNotNull { ref ->
                            byReference[ref]?.takeIf {
                                TvRules.nowNext(state.programmes[ref].orEmpty(), now).first !=
                                    null
                            }
                        }.take(12)
                        if (onAir.isNotEmpty()) {
                            item("now") {
                                TvNowRow(onAir, state.programmes, now, onPlay) { menu = it }
                            }
                        }
                        if (state.selection.category == null) {
                            state.sections.forEachIndexed { index, section ->
                                val channels = section.channels.mapNotNull { ref -> byReference[ref] }
                                if (channels.isNotEmpty()) {
                                    item("section:$index:${section.id}") {
                                        TvShortcutRow(
                                            section.labels[Locale.getDefault().language] ?: section.labels["en"]
                                                ?: section.id,
                                            channels,
                                            onPlay,
                                        ) { menu = it }
                                    }
                                }
                            }
                        }
                    }
                    if (state.channels.isEmpty()) {
                        if (state.loading.isNotEmpty()) {
                            items(6) { TvSkeleton() }
                        } else {
                            item("empty") {
                                Column(
                                    Modifier.fillMaxWidth().padding(vertical = 48.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Icon(
                                        Icons.Outlined.LiveTv,
                                        null,
                                        Modifier.size(40.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        stringResource(
                                            if (state.errors.isEmpty()) {
                                                R.string.tv_empty
                                            } else {
                                                R.string.tv_network_error
                                            },
                                        ),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    TextButton(onClick = {
                                        model.refresh(true)
                                    }) { Text(stringResource(R.string.tv_retry)) }
                                }
                            }
                        }
                    }
                    items(state.channels, key = { "channel:${it.ref.sourceId}:${it.ref.id}" }) { channel ->
                        val programmes = state.programmes[channel.ref].orEmpty()
                        if (guide) {
                            TvGuideCard(channel, programmes, state, now, { onPlay(channel) }, { menu = channel }, {
                                programmeChannel =
                                    channel
                            })
                        } else {
                            TvChannelCard(channel, programmes, now, channel.ref in state.personal.favourites, {
                                onPlay(channel)
                            }, {
                                menu =
                                    channel
                            })
                        }
                    }
                    if (state.channels.isNotEmpty()) {
                        item("end") {
                            if (state.paging.isNotEmpty()) {
                                LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 18.dp))
                            } else if (state.nextPages.isNotEmpty()) {
                                TextButton(onClick = model::loadMore) {
                                    Text(stringResource(R.string.tv_more_channels))
                                }
                            } else {
                                Text(
                                    stringResource(R.string.tv_end),
                                    Modifier.fillMaxWidth().padding(vertical = 18.dp),
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
    if (facets) TvFilterSheet(state, model, onDismiss = { facets = false })
    menu?.let { channel ->
        TvChannelSheet(
            channel, state.personal.favourites.contains(channel.ref), { menu = null },
            onFavourite = {
                model.favourite(channel)
                menu = null
            },
            onGuide = {
                programmeChannel = channel
                menu = null
            }, onShare = {
                onShare(channel)
                menu = null
            },
            onMoveUp = {
                model.moveFavourite(channel, -1)
                menu = null
            }.takeIf {
                state.personal.favourites.indexOf(channel.ref) >
                    0
            },
            onMoveDown = {
                model.moveFavourite(channel, 1)
                menu = null
            }.takeIf {
                state.personal.favourites.indexOf(channel.ref) in
                    0 until state.personal.favourites.lastIndex
            },
            onRoom = onRoom?.let { action ->
                {
                    action(channel)
                    menu = null
                }
            },
        )
    }
    programmeChannel?.let { channel ->
        TvProgrammeSheet(channel, state, now, { programmeChannel = null }, { onPlay(channel) })
    }
}

@Composable
private fun TvChannelCard(
    channel: TvChannel,
    programmes: List<TvProgramme>,
    now: Long,
    favourite: Boolean,
    onPlay: () -> Unit,
    onMenu: () -> Unit,
) {
    val schedule = TvRules.nowNext(programmes, now)
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().heightIn(
            min = 140.dp,
        ).clip(RoundedCornerShape(20.dp)).combinedClickable(onClick = onPlay, onLongClick = onMenu),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TvLogo(channel, Modifier.size(64.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        channel.name,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (favourite) {
                        Icon(
                            Icons.Outlined.Star,
                            null,
                            Modifier.size(16.dp),
                            MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                channel.feedName?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    schedule.first?.title ?: stringResource(R.string.tv_live),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val current = schedule.first
                if (current != null) {
                    LinearProgressIndicator(progress = {
                        TvRules.progress(current, now)
                    }, modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)))
                } else {
                    Spacer(Modifier.fillMaxWidth().height(3.dp))
                }
                // The guide arrives progressively. Reserve its lines to prevent cards jumping in height.
                Text(
                    current?.let {
                        "${time(it.startUtc)} – ${time(it.endUtc)}"
                    } ?: " ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    schedule.second?.let { stringResource(R.string.tv_next_programme, time(it.startUtc), it.title) }
                        ?: " ",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Outlined.PlayArrow, stringResource(R.string.tv_play), Modifier.size(22.dp))
        }
    }
}

@Composable
internal fun TvLogo(channel: TvChannel, modifier: Modifier) {
    var previous by remember(channel.ref) { mutableStateOf<Painter?>(null) }
    val context = LocalContext.current
    val motion = appMotionEnabled()
    val image = remember(context, channel.logoUrl, motion) {
        ImageRequest.Builder(context).data(channel.logoUrl).build()
    }
    val retained = remember(channel.ref, image) { previous }
    val opacity = remember(channel.ref, image) { Animatable(if (motion) 0f else 1f) }
    var ready by remember(channel.ref, image) { mutableStateOf<AsyncImagePainter.State.Success?>(null) }
    LaunchedEffect(ready, motion) {
        val decoded = ready ?: return@LaunchedEffect
        if (motion) opacity.animateTo(1f, tween(220)) else opacity.snapTo(1f)
        previous = decoded.painter
    }
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (retained == null) {
            Text(channel.name.take(2).uppercase(), style = MaterialTheme.typography.titleMedium)
        } else {
            Image(retained, null, Modifier.fillMaxSize().padding(7.dp), contentScale = ContentScale.Fit)
        }
        if (channel.logoUrl !=
            null
        ) {
            AsyncImage(
                image,
                channel.name,
                Modifier.fillMaxSize().padding(7.dp).graphicsLayer {
                    alpha =
                        opacity.value
                },
                contentScale = ContentScale.Fit,
                onSuccess = { ready = it },
            )
        }
    }
}

@Composable
private fun TvNowRow(
    channels: List<TvChannel>,
    programmes: Map<TvChannelRef, List<TvProgramme>>,
    now: Long,
    onPlay: (TvChannel) -> Unit,
    onMenu: (TvChannel) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.tv_now),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(channels, key = { "${it.ref.sourceId}:${it.ref.id}" }) { channel ->
                val current = TvRules.nowNext(programmes[channel.ref].orEmpty(), now).first
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.width(244.dp).clip(RoundedCornerShape(20.dp)).combinedClickable(onClick = {
                        onPlay(channel)
                    }, onLongClick = { onMenu(channel) }),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            TvLogo(channel, Modifier.size(36.dp))
                            Text(
                                channel.name,
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp))
                        }
                        Text(
                            current?.title.orEmpty(),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            minLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        current?.let {
                            LinearProgressIndicator(progress = {
                                TvRules.progress(it, now)
                            }, Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)))
                            Text(
                                "${time(it.startUtc)} – ${time(it.endUtc)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvShortcutRow(
    title: String,
    channels: List<TvChannel>,
    onPlay: (TvChannel) -> Unit,
    onMenu: (TvChannel) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(channels, key = { "${it.ref.sourceId}:${it.ref.id}" }) { channel ->
                Column(
                    Modifier.width(94.dp).combinedClickable(onClick = {
                        onPlay(channel)
                    }, onLongClick = { onMenu(channel) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TvLogo(channel, Modifier.size(72.dp))
                    Text(
                        channel.name,
                        Modifier.padding(top = 6.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun TvGuideCard(
    channel: TvChannel,
    programmes: List<TvProgramme>,
    state: TvHomeState,
    now: Long,
    onPlay: () -> Unit,
    onMenu: () -> Unit,
    onProgrammes: () -> Unit,
) {
    val remaining = if (state.day ==
        LocalDate.now()
    ) {
        programmes.filter { it.endUtc > now }.ifEmpty { programmes }
    } else {
        programmes
    }
    val shown = remaining.take(3)
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth().combinedClickable(onClick = onPlay, onLongClick = onMenu),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TvLogo(channel, Modifier.size(40.dp))
                Text(
                    channel.name,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(Icons.Outlined.PlayArrow, stringResource(R.string.tv_play))
            }
            if (programmes.isEmpty()) {
                Text(
                    stringResource(
                        when {
                            channel.ref in state.guideLoading -> R.string.tv_guide_loading
                            channel.ref in state.guideErrors -> R.string.tv_guide_error
                            else -> R.string.tv_guide_missing
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                if (channel.ref in
                    state.guideSaved
                ) {
                    Text(stringResource(R.string.tv_saved), style = MaterialTheme.typography.labelSmall)
                }
                shown.forEach { programme ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            time(programme.startUtc),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (now in
                                programme.startUtc until programme.endUtc
                            ) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Text(
                            programme.title,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (programmes.size > 3) {
                    TextButton(onClick = onProgrammes) {
                        Text(stringResource(R.string.tv_more_programmes))
                    }
                }
            }
        }
    }
}

@Composable
private fun TvSkeleton() {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().height(140.dp),
    ) {
        Row(
            Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(
                    64.dp,
                ).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.fillMaxWidth(
                        .72f,
                    ).height(
                        16.dp,
                    ).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)),
                )
                Box(
                    Modifier.fillMaxWidth(
                        .9f,
                    ).height(
                        12.dp,
                    ).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(6.dp)),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TvFilterSheet(state: TvHomeState, model: TvHomeModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.tv_filters),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            item {
                TvFacetPicker(
                    stringResource(R.string.tv_country),
                    state.countries,
                    state.selection.country,
                    R.string.tv_world,
                ) {
                    model.select(state.selection.copy(country = it))
                }
            }
            item {
                TvFacetPicker(
                    stringResource(R.string.tv_language),
                    state.languages,
                    state.selection.language,
                    R.string.tv_all,
                ) {
                    model.select(state.selection.copy(language = it))
                }
            }
            item {
                TvFacetPicker(
                    stringResource(R.string.tv_category),
                    state.categories,
                    state.selection.category,
                    R.string.tv_all,
                ) {
                    model.select(state.selection.copy(category = it))
                }
            }
            item { Button(onClick = onDismiss, Modifier.fillMaxWidth()) { Text(stringResource(R.string.tv_done)) } }
            items(state.attributions) { credit ->
                Text(
                    credit,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TvFacetPicker(
    title: String,
    values: List<TvFacet>,
    selected: String?,
    allLabel: Int,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { expanded = true }, Modifier.fillMaxWidth()) {
                Text(
                    values.firstOrNull { it.id == selected }?.label(Locale.getDefault().language)
                        ?: stringResource(allLabel),
                    Modifier.weight(1f),
                )
                Icon(Icons.Outlined.ExpandMore, null)
            }
            DropdownMenu(expanded, onDismissRequest = {
                expanded = false
            }, modifier = Modifier.heightIn(max = 360.dp)) {
                DropdownMenuItem(text = { Text(stringResource(allLabel)) }, onClick = {
                    onSelect(null)
                    expanded = false
                })
                values.forEach { value ->
                    DropdownMenuItem(text = { Text(value.label(Locale.getDefault().language)) }, onClick = {
                        onSelect(value.id)
                        expanded =
                            false
                    })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TvChannelSheet(
    channel: TvChannel,
    favourite: Boolean,
    onDismiss: () -> Unit,
    onFavourite: () -> Unit,
    onGuide: () -> Unit,
    onShare: () -> Unit,
    onRoom: (() -> Unit)?,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.padding(bottom = 14.dp),
            ) {
                TvLogo(channel, Modifier.size(56.dp))
                Text(channel.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            channel.availability.forEach { availability ->
                Text(
                    stringResource(
                        when (availability) {
                            nyanime.tv.api.TvAvailability.GEO_RESTRICTED -> R.string.tv_geo_restricted
                            nyanime.tv.api.TvAvailability.INTERMITTENT -> R.string.tv_intermittent
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            listOf(
                Triple(
                    Icons.Outlined.Star,
                    if (favourite) R.string.tv_remove_favourite else R.string.tv_add_favourite,
                    onFavourite,
                ),
                Triple(Icons.Outlined.DateRange, R.string.tv_programming, onGuide),
                Triple(Icons.Outlined.Share, R.string.tv_share, onShare),
            ).plus(
                listOfNotNull(
                    onRoom?.let { Triple(Icons.Outlined.Groups, R.string.tv_room, it) },
                    onMoveUp?.let { Triple(Icons.Outlined.KeyboardArrowUp, R.string.tv_move_up, it) },
                    onMoveDown?.let {
                        Triple(Icons.Outlined.KeyboardArrowDown, R.string.tv_move_down, it)
                    },
                ),
            ).forEach { (icon, label, action) ->
                TextButton(onClick = action, modifier = Modifier.fillMaxWidth()) {
                    Icon(icon, null)
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(label), Modifier.weight(1f))
                }
            }
        }
    }
}

private fun time(utc: Long): String = Instant.ofEpochMilli(
    utc,
).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))

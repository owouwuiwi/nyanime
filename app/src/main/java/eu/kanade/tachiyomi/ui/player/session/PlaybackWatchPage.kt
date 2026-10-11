package eu.kanade.tachiyomi.ui.player.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.discovery.SourceHomeArtwork
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.anime.Episode
import eu.kanade.tachiyomi.data.watch.WatchRecovery
import eu.kanade.tachiyomi.data.watch.recovery
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.player.PlayerSession
import eu.kanade.tachiyomi.ui.player.Sheets
import eu.kanade.tachiyomi.ui.player.controls.components.PlaybackControlButton
import eu.kanade.tachiyomi.ui.player.controls.components.PlaybackPlayPauseGlyph
import eu.kanade.tachiyomi.ui.player.controls.components.PlaybackSeekBar
import eu.kanade.tachiyomi.ui.player.controls.components.WatchPlaybackButton
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
internal fun PlaybackCompactControls(session: PlayerSession, onReduce: () -> Unit) {
    val state by session.snapshot.collectAsState()
    val controls by session.viewModel.controlsShown.collectAsState()
    val room by session.viewModel.watchTogether.state.collectAsState()
    val readAhead by session.viewModel.readAhead.collectAsState()
    val timelinePosition by session.viewModel.timelinePosition.collectAsState()
    val hasPrevious by session.viewModel.hasPreviousEpisode.collectAsState()
    val hasNext by session.viewModel.hasNextEpisode.collectAsState()
    val reduceMotion by Injekt.get<PlayerPreferences>().reduceMotion().collectAsState()
    var scrubbing by remember { mutableStateOf(false) }
    var pending by remember { mutableFloatStateOf(0f) }
    val togetherBusy = room.active &&
        (room.preparingPlayback || room.pendingPlaybackPaused != null || room.resumeSeconds != null)
    Box(Modifier.fillMaxSize()) {
        androidx.compose.animation.AnimatedVisibility(
            controls || state.paused || togetherBusy || scrubbing || state.buffering,
            enter = androidx.compose.animation.fadeIn(
                androidx.compose.animation.core.tween(if (reduceMotion) 0 else 150),
            ),
            exit = androidx.compose.animation.fadeOut(
                androidx.compose.animation.core.tween(if (reduceMotion) 0 else 120),
            ),
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)))
                Row(
                    Modifier.align(Alignment.TopStart).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlaybackControlButton(onClick = onReduce) {
                        Icon(
                            Icons.Filled.ExpandMore,
                            stringResource(R.string.playback_minimize),
                            tint = Color.White,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (!state.live) {
                        PlaybackControlButton(onClick = { session.viewModel.showSheet(Sheets.SubtitleTracks) }) {
                            Icon(
                                Icons.Outlined.Subtitles,
                                stringResource(R.string.playback_subtitles),
                                tint = Color.White,
                            )
                        }
                    }
                    PlaybackControlButton(onClick = {
                        if (state.live) {
                            session.present(PlaybackPresentation.Fullscreen)
                        } else {
                            session.viewModel.showSheet(Sheets.More)
                        }
                    }) {
                        Icon(Icons.Outlined.Settings, stringResource(R.string.playback_options), tint = Color.White)
                    }
                }
                Row(
                    Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    if (!state.live) {
                        PlaybackControlButton(onClick = {
                            session.viewModel.changeEpisode(true)
                        }, enabled = hasPrevious) {
                            Icon(
                                Icons.Filled.SkipPrevious,
                                stringResource(R.string.playback_previous),
                                tint = Color.White.copy(alpha = if (hasPrevious) 1f else 0.35f),
                            )
                        }
                    }
                    if (room.active && !state.live) {
                        WatchPlaybackButton(
                            room, state.buffering, state.paused, enabled = true, reduceMotion = reduceMotion,
                            onClick = session::playPause,
                            onRetry = {
                                if (room.recovery == WatchRecovery.Connection) {
                                    session.viewModel.watchTogether.retryConnection()
                                } else {
                                    session.viewModel.watchTogether.retryFailedCommand()
                                }
                            },
                            modifier = Modifier.size(64.dp), showParticipants = false, showCaption = false,
                        )
                    } else {
                        PlaybackControlButton(
                            onClick = session::playPause,
                            modifier = Modifier.size(64.dp)
                                .background(
                                    Color.Black.copy(alpha = 0.25f),
                                    androidx.compose.foundation.shape.CircleShape,
                                ),
                        ) {
                            if (state.buffering || state.preparingSelection) {
                                CircularProgressIndicator(
                                    Modifier.size(56.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp,
                                )
                            }
                            PlaybackPlayPauseGlyph(
                                paused = state.paused,
                                reduceMotion = reduceMotion,
                                modifier = Modifier.size(40.dp),
                                contentDescription = stringResource(
                                    if (state.paused) R.string.playback_play else R.string.playback_pause,
                                ),
                            )
                        }
                    }
                    if (!state.live) {
                        PlaybackControlButton(onClick = session::next, enabled = hasNext) {
                            Icon(
                                Icons.Filled.SkipNext,
                                stringResource(R.string.playback_next),
                                tint = Color.White.copy(alpha = if (hasNext) 1f else 0.35f),
                            )
                        }
                    }
                }

                if (state.seekable) {
                    PlaybackSeekBar(
                        value = when {
                            scrubbing -> pending.toDouble()
                            state.live -> state.positionSeconds
                            else -> timelinePosition.toDouble()
                        },
                        duration = state.durationSeconds,
                        onChange = {
                            scrubbing = true
                            pending = it.toFloat()
                        },
                        onFinished = {
                            session.seek(pending.toDouble())
                            scrubbing = false
                        },
                        onCancelled = { scrubbing = false },
                        modifier = Modifier.align(Alignment.BottomCenter),
                        buffered = readAhead.toDouble(),
                        bottomAligned = true,
                        reduceMotion = reduceMotion,
                        advancing = !state.paused && !state.buffering,
                    )
                }
                // Place actions above the rail in the input tree: their lower hit areas must not seek.
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 12.dp, start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (state.live) {
                            stringResource(R.string.tv_live)
                        } else {
                            "${playbackTime(
                                if (scrubbing) pending.toDouble() else timelinePosition.toDouble(),
                            )} / " +
                                playbackTime(state.durationSeconds)
                        },
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.weight(1f))
                    PlaybackControlButton(onClick = { session.present(PlaybackPresentation.Fullscreen) }) {
                        Icon(
                            Icons.Filled.Fullscreen,
                            stringResource(R.string.playback_fullscreen),
                            tint = Color.White,
                        )
                    }
                }
            }
        }
        room.resumeSeconds?.let { seconds ->

            Text(
                stringResource(R.string.playback_room_starting, seconds),
                Modifier.align(Alignment.Center).padding(top = 108.dp),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** The visible rail is 2 dp; its independent touch/accessible target remains 48 dp tall. */
internal fun playbackTime(value: Double): String {
    val seconds = value.takeIf { it.isFinite() && it > 0 }?.toLong() ?: 0
    return if (seconds >=
        3600
    ) {
        "%d:%02d:%02d".format(java.util.Locale.ROOT, seconds / 3600, seconds / 60 % 60, seconds % 60)
    } else {
        "%d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60)
    }
}

/** Keep the page warm while minimized, without placing invisible children in the input tree. */
internal fun Modifier.playbackVisibility(visibility: () -> Float): Modifier =
    this.layout { measurable, constraints ->
        val child = measurable.measure(constraints)
        layout(child.width, child.height) {
            if (visibility() > 0f) child.place(0, 0)
        }
    }.graphicsLayer { alpha = visibility() }

@Composable
internal fun PlaybackWatchPage(
    session: PlayerSession,
    video: PlaybackRect,
    wide: Boolean,
    visibility: () -> Float,
    activity: BaseActivity,
    list: LazyListState,
    query: String,
    onQuery: (String) -> Unit,
    hideSeen: Boolean,
    onHideSeen: (Boolean) -> Unit,
) {
    val state by remember(session) {
        session.snapshot.map { it.copy(positionSeconds = 0.0) }.distinctUntilChanged()
    }.collectAsState(initial = session.snapshot.value)
    val title by session.viewModel.currentAnime.collectAsState()
    val episodes by session.viewModel.currentPlaylist.collectAsState()
    val room by session.viewModel.watchTogether.state.collectAsState()
    val hasNext by session.viewModel.hasNextEpisode.collectAsState()
    val prompt by session.viewModel.nextEpisodePrompt.collectAsState()
    val playbackLoad by session.viewModel.playbackLoadState.collectAsState()
    val skipText by session.viewModel.skipIntroText.collectAsState()
    val reduceMotion by Injekt.get<PlayerPreferences>().reduceMotion().collectAsState()
    val density = LocalDensity.current
    val suggestions = remember { PlaybackRecommendations() }
    var related by remember { mutableStateOf(PlaybackSuggestions()) }
    var filters by remember { mutableStateOf(false) }
    val episodeList = rememberLazyListState()
    LaunchedEffect(title?.id) {
        related = PlaybackSuggestions()
        val anime = title ?: return@LaunchedEffect
        suggestions.observe(anime).collect { related = it }
    }
    LaunchedEffect(title?.id) {
        try {
            title?.let { suggestions.refresh(it) }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // Cached relationships and library suggestions remain available offline.
        }
    }
    val displayed = remember(episodes, query, hideSeen, state.episodeId) {
        episodes.filter {
            (!hideSeen || !it.seen || it.id == state.episodeId) && it.name.contains(query, ignoreCase = true)
        }
    }
    LaunchedEffect(state.episodeId, displayed) {
        val index = displayed.indexOfFirst { it.id == state.episodeId }
        if (index >= 0 && !episodeList.isScrollInProgress) episodeList.scrollToItem(index)
    }
    val modifier = if (wide) {
        Modifier.padding(start = with(density) { video.width.toDp() }, top = with(density) { video.y.toDp() })
    } else {
        Modifier.padding(top = with(density) { (video.y + video.height).toDp() })
    }
    LazyColumn(
        state = list,
        modifier = modifier.fillMaxSize().playbackVisibility(visibility),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "heading") {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
                Text(
                    state.title.ifBlank { stringResource(R.string.playback_preparing) },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    state.episode,
                    Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item(key = "actions") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    WatchAction(Icons.Filled.People, stringResource(R.string.playback_room)) {
                        session.viewModel.showSheet(Sheets.WatchTogether)
                    }
                }
                title?.let { anime ->
                    item {
                        WatchAction(Icons.Outlined.Info, stringResource(R.string.playback_info)) {
                            openTitleDetails(session, activity, anime)
                        }
                    }
                }
                item {
                    WatchAction(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.playback_browse)) {
                        session.present(PlaybackPresentation.Mini)
                    }
                }
            }
        }
        if (playbackLoad.failure != null) {
            item(key = "failure") {
                Surface(
                    Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.playback_start_failed), Modifier.weight(1f))
                        TextButton(onClick = session.viewModel::retryPlayback) {
                            Text(stringResource(R.string.playback_retry))
                        }
                    }
                }
            }
        }
        if (room.active && (room.skip != null || room.next != null)) {
            item(key = "room_cues") {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    eu.kanade.tachiyomi.ui.watch.WatchRoomCues(
                        room,
                        { session.viewModel.watchTogether.requestSkip() },
                        { session.viewModel.watchTogether.cancelSkip() },
                        session.viewModel::playNextEpisodeNow,
                        session.viewModel::cancelNextEpisode,
                        reduceMotion = reduceMotion,
                        artwork = { title?.let { SourceHomeArtwork(it, Modifier.fillMaxSize()) } },
                    )
                }
            }
        } else if (prompt != null) {
            val next = episodes.firstOrNull { it.id == prompt?.episodeId }
            if (next != null) {
                item(key = "countdown") {
                    eu.kanade.tachiyomi.ui.player.controls.components.NextEpisodeCard(
                        seriesTitle = title?.title.orEmpty(),
                        episodeTitle = next.name,
                        secondsRemaining = requireNotNull(prompt).secondsRemaining,
                        onPlayNow = session.viewModel::playNextEpisodeNow,
                        onCancel = session.viewModel::cancelNextEpisode,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        reduceMotion = reduceMotion,
                        artwork = { title?.let { SourceHomeArtwork(it, Modifier.fillMaxSize()) } },
                    )
                }
            }
        }
        skipText?.let { text ->
            item(key = "skip") {
                TextButton(onClick = session.viewModel::onSkipIntro, Modifier.padding(horizontal = 8.dp)) { Text(text) }
            }
        }
        if (hasNext && prompt == null) {
            val currentIndex = episodes.indexOfFirst { it.id == state.episodeId }
            val next = episodes.getOrNull(currentIndex + 1)
            item(key = "next") {
                Surface(
                    onClick = session::next,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        title?.let {
                            SourceHomeArtwork(it, Modifier.size(48.dp, 64.dp).clip(RoundedCornerShape(6.dp)))
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stringResource(R.string.playback_next),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(next?.name.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 2)
                        }
                        Icon(Icons.Filled.SkipNext, null, Modifier.padding(start = 12.dp))
                    }
                }
            }
        }
        if (episodes.isNotEmpty()) {
            item(key = "episode_heading") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.playback_episodes),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    PlaybackControlButton(onClick = { filters = !filters }) {
                        Icon(Icons.Outlined.Search, stringResource(R.string.playback_find_episode))
                    }
                    TextButton(onClick = session.viewModel::showEpisodeListDialog) {
                        Text(stringResource(R.string.playback_all_episodes, episodes.size))
                    }
                }
                androidx.compose.animation.AnimatedVisibility(filters || query.isNotEmpty() || hideSeen) {
                    Column(
                        Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            query,
                            onQuery,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.playback_find_episode)) },
                            leadingIcon = { Icon(Icons.Outlined.Search, null) },
                            shape = RoundedCornerShape(12.dp),
                        )
                        FilterChip(
                            selected = hideSeen,
                            onClick = { onHideSeen(!hideSeen) },
                            label = { Text(stringResource(R.string.playback_unseen)) },
                        )
                    }
                }
            }
            item(key = "episode_playlist") {
                LazyRow(
                    state = episodeList,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(displayed, key = { "episode:${it.id}" }) { episode ->
                        PlaybackEpisodeCard(episode, episode.id == state.episodeId, title) {
                            episode.id?.let {
                                if (it == state.episodeId) session.playPause() else session.changeEpisode(it)
                            }
                        }
                    }
                }
            }
        }
        related.related.forEach { group ->
            item(key = "related_heading:${group.name}") { SuggestionHeading(group.name) }
            items(group.anime, key = { "related:${group.name}:${it.id}" }) {
                PlaybackSuggestionCard(it, session, activity)
            }
        }
        if (related.recommendations.isNotEmpty()) {
            item(key = "recommendations_heading") { SuggestionHeading(stringResource(R.string.playback_recommended)) }
            items(related.recommendations, key = { "recommendation:${it.id}" }) {
                PlaybackSuggestionCard(it, session, activity)
            }
        }
    }
}

@Composable
private fun WatchAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            Modifier.height(48.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, null, Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun PlaybackEpisodeCard(episode: Episode, selected: Boolean, title: Anime?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        modifier = Modifier.width(216.dp),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            title?.let {
                SourceHomeArtwork(it, Modifier.size(40.dp, 56.dp).clip(RoundedCornerShape(5.dp)))
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    episode.name,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val progress = episode.last_second_seen.toDouble() / episode.total_seconds.coerceAtLeast(1)
                if (episode.seen || progress > 0) {
                    LinearProgressIndicator(
                        progress = { if (episode.seen) 1f else progress.toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        drawStopIndicator = {},
                    )
                }
            }
            if (selected) Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp).padding(start = 4.dp))
        }
    }
}

@Composable
private fun SuggestionHeading(name: String) {
    Text(
        name,
        Modifier.padding(horizontal = 16.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun PlaybackSuggestionCard(anime: Anime, session: PlayerSession, activity: BaseActivity) {
    Column {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clickable { session.playRecommended(anime) }) {
            SourceHomeArtwork(anime, Modifier.fillMaxSize(), background = !anime.backgroundUrl.isNullOrBlank())
            Box(
                Modifier.align(Alignment.BottomEnd).padding(12.dp)
                    .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.72f)).padding(6.dp),
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    stringResource(R.string.playback_play),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            SourceHomeArtwork(anime, Modifier.size(40.dp, 48.dp).clip(RoundedCornerShape(6.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    anime.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val genres = anime.genre.orEmpty().take(3).joinToString(" · ")
                if (genres.isNotEmpty()) {
                    Text(
                        genres,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            PlaybackControlButton(onClick = { openTitleDetails(session, activity, anime) }) {
                Icon(Icons.Outlined.Info, stringResource(R.string.playback_info))
            }
        }
    }
}

private fun openTitleDetails(session: PlayerSession, activity: BaseActivity, anime: Anime) {
    session.present(PlaybackPresentation.Mini)
    activity.startActivity(
        android.content.Intent(activity, MainActivity::class.java).apply {
            action = eu.kanade.tachiyomi.core.common.Constants.SHORTCUT_ANIME
            putExtra(eu.kanade.tachiyomi.core.common.Constants.ANIME_EXTRA, anime.id)
        },
    )
}

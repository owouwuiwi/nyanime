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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.outlined.Share
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import it.lorelore.nyanime.presentation.player.VideoActionChip
import it.lorelore.nyanime.presentation.player.VideoChromeActions
import it.lorelore.nyanime.presentation.player.VideoChromeState
import it.lorelore.nyanime.presentation.player.VideoPlaybackChrome
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
internal fun PlaybackCompactControls(session: PlayerSession, onReduce: () -> Unit) {
    if (session.snapshot.value.live) {
        LiveCompactPlaybackControls(session, onReduce)
    } else {
        eu.kanade.tachiyomi.ui.player.controls.MpvPlaybackChrome(session, fullscreen = false, onBack = onReduce)
    }
}

@Composable
private fun LiveCompactPlaybackControls(session: PlayerSession, onReduce: () -> Unit) {
    val state by session.snapshot.collectAsState()
    val runtime by session.live.collectAsState()
    val live = runtime ?: return
    val native by live.output.status.collectAsState()
    val channel by live.session.state.collectAsState()
    val rewindStart = native.rewindStart
    val rewindEnd = native.rewindEnd
    val rewind = channel.stream?.rewind == true && rewindStart != null && rewindEnd != null && rewindEnd > rewindStart
    var seeking by remember(state.sessionId) { mutableStateOf<Float?>(null) }
    var cast by remember { mutableStateOf(false) }
    if (cast) {
        eu.kanade.tachiyomi.ui.cast.CastDevicesDialog(request = session::castRequest, onDismiss = {
            cast = false
        })
    }
    val controls by session.viewModel.controlsShown.collectAsState()
    Box(Modifier.fillMaxSize()) {
        VideoPlaybackChrome(
            state = VideoChromeState(
                visible = controls || state.buffering,
                playing = !state.paused,
                buffering = state.buffering || state.preparingSelection,
                title = state.title,
                subtitle = state.episode,
                durationMs = 0,
                live = true,
            ),
            actions = VideoChromeActions(
                playPause = session::playPause,
                back = onReduce,
                settings = { session.present(PlaybackPresentation.Fullscreen) },
                fullscreen = { session.present(PlaybackPresentation.Fullscreen) },
                live = { session.live.value?.session?.live() },
                cast = {
                    if (session.castRequest() !=
                        null
                    ) {
                        cast = true
                    } else {
                        session.present(PlaybackPresentation.Fullscreen)
                    }
                },
            ),
            position = { 0L },
            modifier = Modifier.padding(bottom = if (rewind && controls) 40.dp else 0.dp),
        )
        if (rewind && controls && rewindStart != null && rewindEnd != null) {
            androidx.compose.material3.Slider(
                value = (seeking ?: native.position?.toFloat() ?: rewindEnd.toFloat()).coerceIn(
                    rewindStart.toFloat(),
                    rewindEnd.toFloat(),
                ),
                onValueChange = { seeking = it },
                onValueChangeFinished = {
                    seeking?.let { live.session.seek(it.toDouble()) }
                    seeking = null
                },
                valueRange = rewindStart.toFloat()..rewindEnd.toFloat(),
                enabled = channel.ready,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp),
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
    var roomConversation by rememberSaveable { mutableStateOf(false) }
    val pageVisible by remember(visibility) { derivedStateOf { visibility() > 0f } }
    LaunchedEffect(room.active, pageVisible) {
        if (!room.active || !pageVisible) roomConversation = false
    }
    eu.kanade.tachiyomi.ui.watch.WatchPlayerRoomPanel(
        visible = roomConversation && room.active && pageVisible,
        onRoomDetails = { session.viewModel.showSheet(Sheets.WatchTogether) },
        onDismiss = { roomConversation = false },
    )
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
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "heading") {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    state.episode.ifBlank { state.title }.ifBlank { stringResource(R.string.playback_preparing) },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(enabled = title != null) {
                        title?.let { openTitleDetails(session, activity, it) }
                    }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    title?.let { SourceHomeArtwork(it, Modifier.size(44.dp).clip(CircleShape)) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            state.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(R.string.playback_all_episodes, episodes.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.Outlined.Info,
                        stringResource(R.string.playback_info),
                        Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item(key = "actions") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    WatchAction(
                        Icons.Outlined.Share,
                        stringResource(R.string.playback_share),
                        session::shareCurrentContent,
                    )
                }
                item {
                    WatchAction(
                        Icons.Filled.People,
                        stringResource(if (room.active) R.string.room_chat else R.string.playback_room),
                    ) {
                        if (room.active) roomConversation = true else session.viewModel.showSheet(Sheets.WatchTogether)
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
    VideoActionChip(icon, label, onClick)
}

@Composable
private fun PlaybackEpisodeCard(episode: Episode, selected: Boolean, title: Anime?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
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

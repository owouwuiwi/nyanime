package eu.kanade.tachiyomi.ui.tv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.tv.TvMpvOutput
import eu.kanade.tachiyomi.data.tv.TvPlaybackFailure
import eu.kanade.tachiyomi.data.tv.TvPlayerSession
import eu.kanade.tachiyomi.data.tv.TvSelection
import eu.kanade.tachiyomi.data.tv.TvServices
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import nyanime.tv.api.TvChannel
import nyanime.tv.api.TvRules

@Composable
internal fun TvPlayerPreparing(onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(12.dp)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.tv_back), tint = Color.White)
        }
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            CircularProgressIndicator(Modifier.size(36.dp), color = Color.White, strokeWidth = 2.dp)
            Text(
                stringResource(R.string.tv_connecting),
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
internal fun TvPlayerError(onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(Icons.Outlined.LiveTv, null, Modifier.size(36.dp), tint = Color.White)
            Text(stringResource(R.string.tv_player_unavailable), color = Color.White)
            Button(onClick = onBack) { Text(stringResource(R.string.tv_back)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TvPlayerControls(
    session: TvPlayerSession,
    output: TvMpvOutput,
    services: TvServices,
    pip: StateFlow<Boolean>,
    onBack: () -> Unit,
    onShare: (TvChannel) -> Unit,
    onChannel: (TvChannel) -> Unit,
    onRoom: (TvChannel) -> Unit,
    rooms: eu.kanade.tachiyomi.data.watch.WatchTogetherManager,
    onPresentationDrag: ((Float) -> Unit)? = null,
    onPresentationDragEnd: ((Boolean) -> Unit)? = null,
) {
    val drag by androidx.compose.runtime.rememberUpdatedState(onPresentationDrag)
    val end by androidx.compose.runtime.rememberUpdatedState(onPresentationDragEnd)
    val state by session.state.collectAsState()
    val room by rooms.controller.state.collectAsState()
    val liveRoom by rooms.tv.state.collectAsState()
    var roomSheet by remember { mutableStateOf(false) }
    var socialPanel by remember { mutableStateOf(false) }
    var castDialog by remember { mutableStateOf(false) }
    val native by output.status.collectAsState()
    val inPip by pip.collectAsState()
    LaunchedEffect(inPip, room.active) {
        if (inPip || !room.active) socialPanel = false
    }
    val personal by services.personal.changes.collectAsState(initial = "")
    val favourites = remember(personal) { services.personal.read().favourites }
    var visible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var panel by remember { mutableStateOf<String?>(null) }
    var channels by remember { mutableStateOf<List<TvChannel>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var channelsLoading by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf(false) }
    val motion = appMotionEnabled()
    val duration = if (motion) ModernMotion.PAGE_MILLIS else 0
    val channel = state.media?.channel
    val show = visible || state.paused || state.failed || panel != null
    LaunchedEffect(show, state.ready, state.paused, state.failed, interaction, panel) {
        if (show && state.ready && !state.paused && !state.failed && panel == null) {
            delay(4000)
            visible = false
        }
    }
    LaunchedEffect(state.reference) {
        visible = true
        interaction++
        actionError = false
    }
    LaunchedEffect(panel) {
        if (panel != "channels") return@LaunchedEffect
        channelsLoading = true
        try {
            channels = services.registry.state.value.values.flatMap { registration ->
                val selection = services.personal.read().sources[registration.source.id.toString()]
                    ?: TvSelection(registration.source.capabilities.initialCountry)
                services.repository.cachedCatalogue(
                    registration.source.id,
                    nyanime.tv.api.TvCatalogueRequest(selection.country, selection.language, selection.category),
                ).data?.channels.orEmpty()
            }
                .filter {
                    services.adultContent.value || !it.adult
                }.distinctBy { it.ref }.sortedBy { it.name.lowercase() }
        } finally {
            channelsLoading = false
        }
    }
    if (inPip) return
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            var center = false
            detectVerticalDragGestures(
                onDragStart = { center = it.x in size.width * .35f..size.width * .65f },
                onVerticalDrag = { change, amount ->
                    if (center) {
                        change.consume()
                        drag?.invoke(amount)
                    }
                },
                onDragEnd = { if (center) end?.invoke(false) },
                onDragCancel = { if (center) end?.invoke(true) },
            )
        }.pointerInput(state.ready) {
            detectTapGestures(onTap = {
                visible = !visible
                interaction++
            })
        },
    ) {
        AnimatedVisibility(show, enter = fadeIn(tween(duration)), exit = fadeOut(tween(duration))) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = .72f), Color.Transparent, Color.Black.copy(alpha = .8f)),
                    ),
                ),
            ) {
                Row(
                    Modifier.align(
                        Alignment.TopCenter,
                    ).fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            stringResource(R.string.tv_back),
                            tint = Color.White,
                        )
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(
                            channel?.name ?: stringResource(R.string.tv_title),
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            channel?.feedName ?: stringResource(R.string.tv_live),
                            color = Color.White.copy(alpha = .72f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = {
                        castDialog = true
                    }, enabled = channel != null) {
                        Icon(Icons.Outlined.Cast, stringResource(R.string.tv_cast), tint = Color.White)
                    }
                    channel?.let { selected ->
                        IconButton(onClick = {
                            onShare(selected)
                            interaction++
                        }) { Icon(Icons.Outlined.Share, stringResource(R.string.tv_share), tint = Color.White) }
                        IconButton(onClick = {
                            actionError =
                                runCatching {
                                    services.personal.favourite(selected.ref, selected.ref !in favourites)
                                }.isFailure
                            interaction++
                        }) {
                            Icon(
                                if (selected.ref in
                                    favourites
                                ) {
                                    Icons.Outlined.Star
                                } else {
                                    Icons.Outlined.StarOutline
                                },
                                stringResource(
                                    if (selected.ref in
                                        favourites
                                    ) {
                                        R.string.tv_remove_favourite
                                    } else {
                                        R.string.tv_add_favourite
                                    },
                                ),
                                tint = Color.White,
                            )
                        }
                    }
                }
                if (!state.loading && !state.buffering && !state.failed) {
                    FilledIconButton(
                        onClick = {
                            if (state.paused) session.live() else session.pause(true)
                            interaction++
                        },
                        modifier = Modifier.align(Alignment.Center).size(68.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color.White.copy(alpha = .16f),
                            contentColor = Color.White,
                        ),
                    ) {
                        Icon(
                            if (state.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                            stringResource(if (state.paused) R.string.tv_resume_live else R.string.tv_pause),
                            Modifier.size(36.dp),
                        )
                    }
                }
                Column(
                    Modifier.align(
                        Alignment.BottomCenter,
                    ).fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (actionError) {
                        Text(
                            stringResource(R.string.tv_settings_error),
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (room.liveMode) {
                        Text(
                            stringResource(
                                if (liveRoom.adjustment ==
                                    nyanime.tv.api.TvLiveAdjustment.Unverified
                                ) {
                                    R.string.tv_room_unverified
                                } else {
                                    R.string.tv_room_local_controls
                                },
                            ),
                            color = Color.White.copy(alpha = .8f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (state.paused) {
                        Text(
                            stringResource(R.string.tv_paused_local),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    val rewindStart = native.rewindStart
                    val rewindEnd = native.rewindEnd
                    if (state.stream?.rewind == true &&
                        rewindStart != null &&
                        rewindEnd != null &&
                        rewindEnd > rewindStart
                    ) {
                        var seeking by remember(state.reference) { mutableStateOf<Float?>(null) }
                        Slider(
                            value = (seeking ?: native.position?.toFloat() ?: rewindEnd.toFloat()).coerceIn(
                                rewindStart.toFloat(),
                                rewindEnd.toFloat(),
                            ),
                            onValueChange = {
                                seeking = it
                                interaction++
                            },
                            onValueChangeFinished = {
                                seeking?.let { session.seek(it.toDouble()) }
                                seeking = null
                            },
                            valueRange = rewindStart.toFloat()..rewindEnd.toFloat(),
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state.ready,
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Surface(
                            color = Color.White.copy(alpha = .12f),
                            contentColor = Color.White,
                            shape = RoundedCornerShape(50),
                        ) {
                            TextButton(onClick = {
                                session.live()
                                interaction++
                            }, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
                                Box(Modifier.size(6.dp).background(Color.White, CircleShape))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.tv_live), fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        if (!state.room) {
                            TextButton(
                                onClick = {
                                    session.toggleEnhancement()
                                    interaction++
                                },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = if (state.enhanced) Color.White else Color.White.copy(alpha = .5f),
                                ),
                            ) {
                                Text("SM", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = {
                            panel = "programme"
                            interaction++
                        }, enabled = channel != null) {
                            Icon(Icons.Outlined.Schedule, stringResource(R.string.tv_programming), tint = Color.White)
                        }
                        IconButton(onClick = {
                            panel = "channels"
                            interaction++
                        }, enabled = !room.active || room.host) {
                            Icon(Icons.Outlined.LiveTv, stringResource(R.string.tv_channels), tint = Color.White)
                        }
                        if (room.active) {
                            eu.kanade.tachiyomi.ui.watch.WatchPlayerRoomAccess(room) { socialPanel = true }
                        } else {
                            IconButton(onClick = { channel?.let(onRoom) }) {
                                Icon(Icons.Outlined.Group, stringResource(R.string.tv_room), tint = Color.White)
                            }
                        }
                        IconButton(onClick = {
                            panel = "volume"
                            interaction++
                        }) {
                            Icon(
                                Icons.AutoMirrored.Outlined.VolumeUp,
                                stringResource(R.string.tv_volume),
                                tint = Color.White,
                            )
                        }
                        IconButton(onClick = {
                            panel = "quality"
                            interaction++
                        }, enabled = state.media != null) {
                            Icon(
                                Icons.Outlined.Settings,
                                stringResource(R.string.tv_playback_options),
                                tint = Color.White,
                            )
                        }
                    }
                }
            }
        }
        eu.kanade.tachiyomi.ui.watch.WatchPlayerRoomPanel(
            visible = socialPanel && room.active && panel == null && !roomSheet && !castDialog,
            onRoomDetails = { roomSheet = true },
            onDismiss = { socialPanel = false },
        )
        if (room.active && panel == null && !roomSheet && !castDialog) {
            eu.kanade.tachiyomi.ui.watch.WatchRoomPlayerSocial(
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(end = 16.dp, top = 68.dp),
            )
        }
        if (state.loading || state.buffering || state.failed) {
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (!state.failed) {
                    CircularProgressIndicator(
                        Modifier.size(36.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                }
                Text(
                    stringResource(
                        if (state.failed &&
                            services.registry.state.value[state.reference?.sourceId] == null
                        ) {
                            R.string.tv_missing_source
                        } else if (state.failed) {
                            when (state.failure) {
                                TvPlaybackFailure.ADDRESS -> R.string.tv_address_unavailable
                                TvPlaybackFailure.ACCESS -> R.string.tv_access_denied
                                TvPlaybackFailure.UNAVAILABLE -> R.string.tv_stream_unavailable
                                TvPlaybackFailure.UNSUPPORTED -> R.string.tv_format_unsupported
                                TvPlaybackFailure.TLS -> R.string.tv_secure_connection_failed
                                else -> R.string.tv_player_unavailable
                            }
                        } else if (state.failure?.retryable == false) {
                            R.string.tv_trying_alternative
                        } else if (state.retryAttempt >
                            0
                        ) {
                            R.string.tv_reconnecting
                        } else {
                            R.string.tv_connecting
                        },
                    ),
                    color = Color.White,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.background(
                        Color.Black.copy(alpha = .6f),
                        RoundedCornerShape(18.dp),
                    ).padding(horizontal = 18.dp, vertical = 10.dp),
                )
                if (state.failed) {
                    if (nyanime.tv.api.TvAvailability.GEO_RESTRICTED in channel?.availability.orEmpty()) {
                        Text(
                            stringResource(R.string.tv_geo_restricted),
                            color = Color.White.copy(alpha = .72f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Button(onClick = {
                        state.reference?.let(session::select)
                        interaction++
                    }) { Text(stringResource(R.string.tv_retry)) }
                    if (!room.active || room.host) {
                        TextButton(onClick = {
                            panel = "channels"
                            interaction++
                        }) { Text(stringResource(R.string.tv_choose_channel), color = Color.White) }
                    }
                }
            }
        }
    }
    if (panel == "programme" && channel != null) {
        var programmeState by remember(channel.ref) { mutableStateOf(TvHomeState()) }
        LaunchedEffect(channel.ref) {
            val day = java.time.LocalDate.now()
            val zone = java.time.ZoneId.systemDefault()
            services.repository.guideFlow(
                nyanime.tv.api.TvGuideRequest(
                    listOf(channel.ref),
                    day.atStartOfDay(zone).toInstant().toEpochMilli(),
                    day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                ),
            ).collect { result ->
                programmeState =
                    programmeState.copy(
                        day = day,
                        programmes = result.data.programmes.groupBy { it.channel },
                        guideLoading = result.loading,
                        guideSaved = result.saved,
                        guideUnavailable = result.data.unavailable,
                        guideErrors = result.data.failed,
                    )
            }
        }
        TvProgrammeSheet(
            channel,
            programmeState,
            System.currentTimeMillis(),
            { panel = null },
            { panel = null },
            actionLabel = R.string.tv_back_to_video,
        )
    }
    if (castDialog) {
        eu.kanade.tachiyomi.ui.cast.CastDevicesDialog(
            request = { null },
            onDismiss = { castDialog = false },
            liveRequest = {
                state.reference?.let { eu.kanade.tachiyomi.data.cast.TvCastRequest(it, state.stream?.id) }
            },
        )
    }
    if (roomSheet) eu.kanade.tachiyomi.ui.watch.WatchTogetherSheet { roomSheet = false }
    if (panel != null &&
        panel != "programme"
    ) {
        ModalBottomSheet(onDismissRequest = {
            panel = null
            interaction++
        }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            when (panel) {
                "channels" -> Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(horizontal = 16.dp)) {
                    Text(stringResource(R.string.tv_channels), style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(query, {
                        query = it
                    }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), label = {
                        Text(stringResource(R.string.tv_find_channel))
                    })
                    if (channelsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    LazyColumn {
                        items(
                            channels.filter {
                                query.isBlank() || it.name.contains(query, true)
                            },
                            key = { "${it.ref.sourceId}:${it.ref.id}" },
                        ) { selected ->
                            TextButton(onClick = {
                                onChannel(selected)
                                panel = null
                                query = ""
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    selected.name,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (selected.ref == state.reference) Icon(Icons.Outlined.Check, null)
                            }
                        }
                    }
                }
                "volume" -> Column(Modifier.padding(24.dp)) {
                    Text(stringResource(R.string.tv_volume), style = MaterialTheme.typography.titleLarge)
                    Slider(native.volume.toFloat(), { output.volume(it.toDouble()) }, valueRange = 0f..100f)
                }
                "quality" -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(
                        stringResource(R.string.tv_playback_options),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(8.dp),
                    )
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        item("quality-label") {
                            Text(
                                stringResource(R.string.tv_quality),
                                Modifier.padding(8.dp),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        items(state.media?.streams.orEmpty().withIndex().toList(), key = {
                            "quality:${it.value.id}"
                        }) { option ->
                            TextButton(onClick = {
                                session.quality(option.index)
                                panel = null
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text(option.value.label, Modifier.weight(1f))
                                if (state.streamIndex == option.index) Icon(Icons.Outlined.Check, null)
                            }
                        }
                        if (native.tracks.any { it.type == "audio" }) {
                            item("audio-label") {
                                Text(
                                    stringResource(R.string.tv_audio),
                                    Modifier.padding(8.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                            item("audio-auto") {
                                TvTrackChoice(stringResource(R.string.tv_automatic), native.audio == "auto") {
                                    output.track("audio", "auto")
                                }
                            }
                            items(native.tracks.filter { it.type == "audio" }, key = { "audio:${it.id}" }) { track ->
                                TvTrackChoice(track.label, native.audio == track.id.toString()) {
                                    output.track(track.type, track.id.toString())
                                }
                            }
                        }
                        if (native.tracks.any { it.type == "sub" }) {
                            item("sub-label") {
                                Text(
                                    stringResource(R.string.tv_subtitles),
                                    Modifier.padding(8.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                            item("sub-off") {
                                TvTrackChoice(stringResource(R.string.tv_off), native.subtitle == "no") {
                                    output.track("sub", "no")
                                }
                            }
                            item("sub-auto") {
                                TvTrackChoice(
                                    stringResource(R.string.tv_automatic),
                                    native.subtitle == "auto",
                                ) { output.track("sub", "auto") }
                            }
                            items(native.tracks.filter { it.type == "sub" }, key = { "sub:${it.id}" }) { track ->
                                TvTrackChoice(track.label, native.subtitle == track.id.toString()) {
                                    output.track(track.type, track.id.toString())
                                }
                            }
                        }
                        if (state.stream?.adjustableSpeed == true && !state.room) {
                            item("speed-label") {
                                Text(
                                    stringResource(R.string.tv_speed),
                                    Modifier.padding(8.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                            items(listOf(.75, 1.0, 1.25, 1.5, 2.0), key = { "speed:$it" }) { speed ->
                                TvTrackChoice("$speed×", kotlin.math.abs(native.speed - speed) < .001) {
                                    session.speed(speed)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TvTrackChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (selected) Icon(Icons.Outlined.Check, null)
    }
}

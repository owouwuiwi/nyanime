package eu.kanade.tachiyomi.ui.cast

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.entries.components.ItemCover
import eu.kanade.presentation.theme.LocalNyanimeStyle
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.data.cast.CastDevice
import eu.kanade.tachiyomi.data.cast.CastMedia
import eu.kanade.tachiyomi.data.cast.CastPlayback
import eu.kanade.tachiyomi.data.cast.CastProtocol
import eu.kanade.tachiyomi.data.cast.CastState
import eu.kanade.tachiyomi.data.cast.CastWire
import kotlin.math.roundToInt

internal data class CastRemoteActions(
    val pause: () -> Unit = {},
    val previous: () -> Unit = {},
    val next: () -> Unit = {},
    val seek: (Long) -> Unit = {},
    val volume: (Float) -> Unit = {},
    val adjustVolume: (Float) -> Unit = {},
    val brightness: (Float) -> Unit = {},
    val subtitle: (Int) -> Unit = {},
    val reconnect: () -> Unit = {},
    val returnToPhone: () -> Unit = {},
    val stop: () -> Unit = {},
    val browse: () -> Unit = {},
    val back: () -> Unit = {},
    val quality: (() -> Unit)? = null,
    val playEpisode: (Long) -> Unit = {},
    val room: (() -> Unit)? = null,
)

@Composable
internal fun CastRemoteContent(state: CastState, actions: CastRemoteActions, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints(Modifier.safeDrawingPadding()) {
            val wide = maxWidth >= 680.dp
            Column(
                Modifier.fillMaxWidth().verticalScroll(
                    rememberScrollState(),
                ).padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                RemoteHeader(state, actions)
                eu.kanade.presentation.episodeguide.CastGuideSkippedFeedback(state.skippedFillers, actions.playEpisode)
                Column(Modifier.widthIn(max = 1000.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (state.error != null) ConnectionNotice(state, actions)
                    if (state.media != null) {
                        if (wide) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(24.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                NowCasting(state, Modifier.weight(0.9f))
                                Column(Modifier.weight(1.1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    PlaybackControls(state, actions)
                                    ReceiverControls(state, actions)
                                }
                            }
                        } else {
                            NowCasting(state)
                            PlaybackControls(state, actions)
                            ReceiverControls(state, actions)
                        }
                        Button(
                            onClick = actions.browse,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = if (LocalNyanimeStyle.current) RoundedCornerShape(4.dp) else ButtonDefaults.shape,
                            colors = if (LocalNyanimeStyle.current) {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.onSurface,
                                    contentColor = MaterialTheme.colorScheme.surface,
                                )
                            } else {
                                ButtonDefaults.buttonColors()
                            },
                        ) {
                            Icon(Icons.Default.VideoLibrary, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                if (state.media?.live != null) {
                                    stringResource(eu.kanade.tachiyomi.R.string.tv_choose_channel)
                                } else {
                                    "Scegli un altro video"
                                },
                            )
                        }
                        Text(
                            "La riproduzione continua sulla TV mentre usi l’app.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    } else {
                        Surface(
                            shape = RoundedCornerShape(if (LocalNyanimeStyle.current) 8.dp else 28.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(20.dp),
                            ) {
                                Icon(
                                    Icons.Default.Tv,
                                    null,
                                    Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    if (
                                        state.connecting
                                    ) {
                                        "Prepariamo il video per la TV"
                                    } else {
                                        "La sessione è terminata"
                                    },
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                if (state.connecting) {
                                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                                    Text("Puoi continuare a usare l’app.", style = MaterialTheme.typography.bodyMedium)
                                    TextButton(onClick = actions.stop) { Text("Annulla collegamento") }
                                } else {
                                    Button(onClick = actions.browse) { Text("Torna all’app") }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun RemoteHeader(state: CastState, actions: CastRemoteActions) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text("TELECOMANDO", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(
                state.device?.name ?: "Collegamento alla TV",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        var menu by remember { mutableStateOf(false) }
        actions.room?.let { open ->
            IconButton(onClick = open) {
                Icon(Icons.Default.Group, stringResource(eu.kanade.tachiyomi.R.string.tv_room))
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Opzioni di trasmissione") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Continua sul telefono") },
                    onClick = {
                        menu = false
                        actions.returnToPhone()
                    },
                    enabled = state.active && !state.connecting,
                )
                DropdownMenuItem(text = { Text("Interrompi sulla TV") }, onClick = {
                    menu = false
                    actions.stop()
                })
            }
        }
    }
}

@Composable
private fun NowCasting(state: CastState, modifier: Modifier = Modifier) {
    val media = state.media ?: return
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (LocalNyanimeStyle.current) 8.dp else 28.dp),
        color = colors.surfaceContainer,
    ) {
        Row(
            Modifier.background(
                Brush.linearGradient(listOf(colors.primaryContainer.copy(alpha = 0.6f), colors.surfaceContainer)),
            )
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CastArtwork(media, Modifier.width(96.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = CircleShape, color = colors.surface.copy(alpha = 0.8f)) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (state.connecting || state.playback.buffering) {
                            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.CastConnected, null, Modifier.size(14.dp), tint = colors.primary)
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                state.needsReconnect -> "Disconnesso"
                                state.connecting -> "Collegamento…"
                                state.playback.buffering -> "Caricamento…"
                                state.playback.finished -> "Terminato"
                                state.playback.paused -> "In pausa"
                                else -> "Sulla TV"
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                Text(
                    media.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (media.live !=
                        null
                    ) {
                        androidx.compose.ui.res.stringResource(eu.kanade.tachiyomi.R.string.tv_live)
                    } else {
                        media.episodeName
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (media.quality.isNotBlank()) {
                    Text(
                        media.quality,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
internal fun CastArtwork(media: CastMedia?, modifier: Modifier = Modifier) {
    if (media?.cover?.url != null) {
        ItemCover.Book(
            data = media.cover,
            modifier = modifier,
            shape = RoundedCornerShape(if (LocalNyanimeStyle.current) 4.dp else 14.dp),
        )
    } else {
        Box(
            modifier.aspectRatio(
                2f / 3f,
            ).clip(
                RoundedCornerShape(if (LocalNyanimeStyle.current) 4.dp else 14.dp),
            ).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Tv, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun PlaybackControls(state: CastState, actions: CastRemoteActions) {
    val playback = state.playback
    val live = state.media?.live != null
    val enabled = !state.connecting && !state.needsReconnect
    var seeking by remember(state.media?.url) { mutableStateOf<Float?>(null) }
    val position = seeking?.toLong() ?: playback.positionMs
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (!live || playback.canSeek && playback.durationMs > 0) {
            Slider(
                value = (
                    seeking ?: playback.positionMs.toFloat()
                    ).coerceIn(0f, playback.durationMs.coerceAtLeast(1).toFloat()),
                onValueChange = { seeking = it },
                onValueChangeFinished = {
                    seeking?.let { actions.seek(it.toLong()) }
                    seeking = null
                },
                valueRange = 0f..playback.durationMs.coerceAtLeast(1).toFloat(),
                enabled = enabled && playback.canSeek && playback.durationMs > 0,
                modifier = Modifier.semantics {
                    contentDescription = "Posizione del video"
                    stateDescription =
                        CastWire.clock(
                            position,
                        )
                },
            )
        }
        if (!live ||
            playback.canSeek &&
            playback.durationMs > 0
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(CastWire.clock(position), style = MaterialTheme.typography.labelMedium)
                Text(
                    if (playback.durationMs > 0) CastWire.clock(playback.durationMs) else "—:—",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!live) {
                IconButton(
                    onClick = actions.previous,
                    enabled = enabled && state.canPrevious,
                ) { Icon(Icons.Default.SkipPrevious, "Episodio precedente", Modifier.size(28.dp)) }
            }
            if (!live || playback.canSeek) {
                IconButton(
                    onClick = { actions.seek(playback.positionMs - 10_000) },
                    enabled = enabled && playback.canSeek,
                ) { Icon(Icons.Default.Replay10, "Indietro di 10 secondi", Modifier.size(28.dp)) }
            }
            FilledIconButton(
                onClick = actions.pause,
                enabled = enabled && !playback.finished,
                modifier = Modifier.size(72.dp),
            ) {
                if (state.connecting || playback.buffering) {
                    CircularProgressIndicator(
                        Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                        color = if (enabled) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                } else {
                    Icon(
                        if (playback.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        if (playback.paused) "Riprendi sulla TV" else "Pausa sulla TV",
                        Modifier.size(36.dp),
                    )
                }
            }
            if (!live || playback.canSeek) {
                IconButton(
                    onClick = { actions.seek(playback.positionMs + 10_000) },
                    enabled = enabled && playback.canSeek,
                ) { Icon(Icons.Default.Forward10, "Avanti di 10 secondi", Modifier.size(28.dp)) }
            }
            if (!live) {
                IconButton(
                    onClick = actions.next,
                    enabled = enabled && state.canNext,
                ) { Icon(Icons.Default.SkipNext, "Prossimo episodio", Modifier.size(28.dp)) }
            }
        }
    }
}

@Composable
private fun ReceiverControls(state: CastState, actions: CastRemoteActions) {
    val playback = state.playback
    val enabled = !state.connecting && !state.needsReconnect
    Surface(
        shape = RoundedCornerShape(if (LocalNyanimeStyle.current) 8.dp else 24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (playback.canSetVolume) {
                LevelControl(
                    "Volume della TV",
                    Icons.Default.VolumeUp,
                    playback.volume,
                    enabled,
                    actions.volume,
                    actions.adjustVolume,
                )
            }
            if (playback.canSetBrightness) {
                LevelControl(
                    "Luminosità della TV",
                    Icons.Default.Brightness6,
                    playback.brightness,
                    enabled,
                    actions.brightness,
                )
            }
            if (!playback.canSetVolume && !playback.canSetBrightness) {
                Text(
                    "Per volume e immagine usa il telecomando della TV.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if ((state.device?.protocol == CastProtocol.GOOGLE_CAST || state.playback.canSetSubtitles) &&
                state.media?.subtitles?.isNotEmpty() == true
            ) {
                SubtitlePicker(state, actions, enabled)
            }
            actions.quality?.let { quality ->
                OutlinedButton(
                    onClick = quality,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Qualità e server video") }
            }
            TextButton(
                onClick = actions.returnToPhone,
                enabled = !state.connecting,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Continua sul telefono") }
        }
    }
}

@Composable
private fun LevelControl(
    label: String,
    icon: ImageVector,
    value: Float,
    enabled: Boolean,
    onChange: (Float) -> Unit,
    onStep: ((Float) -> Unit)? = null,
) {
    var editing by remember { mutableStateOf<Float?>(null) }
    val shown = editing ?: value.coerceIn(0f, 1f)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.labelLarge)
            Text(
                (shown * 100).roundToInt().toString() + "%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { if (onStep != null) onStep(-0.05f) else onChange((shown - 0.05f).coerceAtLeast(0f)) },
                enabled = enabled && shown > 0f,
            ) { Icon(Icons.Default.Remove, "Diminuisci " + label.lowercase()) }
            Slider(
                value = shown,
                onValueChange = { editing = it },
                onValueChangeFinished = {
                    editing?.let(onChange)
                    editing = null
                },
                enabled = enabled,
                modifier = Modifier.weight(1f).semantics { contentDescription = label },
            )
            IconButton(
                onClick = { if (onStep != null) onStep(0.05f) else onChange((shown + 0.05f).coerceAtMost(1f)) },
                enabled = enabled && shown < 1f,
            ) { Icon(Icons.Default.Add, "Aumenta " + label.lowercase()) }
        }
    }
}

@Composable
private fun SubtitlePicker(state: CastState, actions: CastRemoteActions, enabled: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Subtitles, null, Modifier.size(20.dp))
            Text(
                state.media?.subtitles?.getOrNull(state.subtitleIndex)?.name ?: "Sottotitoli disattivati",
                Modifier.weight(1f).padding(horizontal = 10.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Default.ExpandMore, null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (listOf("Disattivati") + state.media?.subtitles.orEmpty().map { it.name }).forEachIndexed { index, name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    leadingIcon = { if (state.subtitleIndex == index - 1) Icon(Icons.Default.Check, null) },
                    onClick = {
                        expanded = false
                        actions.subtitle(index - 1)
                    },
                )
            }
        }
    }
}

@Composable
private fun ConnectionNotice(state: CastState, actions: CastRemoteActions) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.WifiOff, null, Modifier.size(22.dp))
                Text(state.error.orEmpty(), style = MaterialTheme.typography.bodyMedium)
            }
            if (state.needsReconnect) {
                Button(onClick = actions.reconnect, enabled = !state.connecting) { Text("Ricollega la TV") }
            }
        }
    }
}

@Preview(widthDp = 393, heightDp = 852)
@Preview(widthDp = 852, heightDp = 393)
@Preview(widthDp = 393, heightDp = 852, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CastRemotePreview() {
    TachiyomiPreviewTheme {
        CastRemoteContent(
            state = CastState(
                device = CastDevice("preview", "TV del salotto", CastProtocol.DLNA),
                media = CastMedia(
                    1,
                    2,
                    3,
                    "Il castello errante",
                    "Episodio 3 · Il viaggio continua",
                    "",
                    "video/mp4",
                    0,
                    1_440_000,
                    quality = "1080p",
                ),
                playback = CastPlayback(
                    487_000,
                    1_440_000,
                    paused = false,
                    canSetVolume = true,
                    volume = 0.4f,
                    canSetBrightness = true,
                ),
                canNext = true,
                canPrevious = true,
            ),
            actions = CastRemoteActions(),
        )
    }
}

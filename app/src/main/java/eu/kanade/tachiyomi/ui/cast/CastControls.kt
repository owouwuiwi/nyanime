package eu.kanade.tachiyomi.ui.cast

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.theme.LocalNyanimeStyle
import eu.kanade.tachiyomi.data.cast.CastController
import eu.kanade.tachiyomi.data.cast.CastDevice
import eu.kanade.tachiyomi.data.cast.CastProtocol
import eu.kanade.tachiyomi.data.cast.CastRequest
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch

@Composable
fun CastDevicesDialog(
    request: () -> CastRequest?,
    onDismiss: () -> Unit,
    liveRequest: (() -> eu.kanade.tachiyomi.data.cast.TvCastRequest?)? = null,
    audioRequest: (suspend () -> eu.kanade.tachiyomi.data.cast.CastAudioRequest?)? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val controller = remember { CastController.get(context) }
    val state by controller.state.collectAsState()
    val pairing by controller.companionPairing.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun connect(device: CastDevice? = null) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val target = device ?: controller.findCompanion(address)
                if (target.protocol == CastProtocol.COMPANION) controller.pairCompanion(target)
                if (audioRequest != null) {
                    controller.playAudio(
                        audioRequest() ?: error(context.getString(eu.kanade.tachiyomi.R.string.cast_audio_unavailable)),
                        target,
                    )
                } else if (liveRequest != null) {
                    controller.playLive(
                        liveRequest() ?: error(context.getString(eu.kanade.tachiyomi.R.string.tv_player_unavailable)),
                        target,
                    )
                } else {
                    val media = request() ?: error("Apri prima un video da trasmettere")
                    controller.play(media, target)
                }
                onDismiss()
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                error = when (e) {
                    is TimeoutCancellationException -> "Conferma scaduta. Riprova e conferma il codice sulla TV."
                    is java.io.IOException -> "La TV non risponde. Controlla che l’app sia aperta e sulla stessa rete."
                    is IllegalArgumentException -> "Collegamento non valido. Controlla l’indirizzo e l’app sulla TV."
                    else -> e.message ?: "Collegamento non riuscito. Riprova."
                }
            } finally {
                busy = false
            }
        }
    }
    DisposableEffect(controller) {
        controller.discover()
        onDispose { controller.stopDiscovery() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Cast, null, Modifier.size(32.dp)) },
        title = {
            Text(
                if (pairing != null) "Collega ${pairing!!.name}" else "Trasmetti alla TV",
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    val code = pairing?.code.orEmpty()
                    if (code.isNotBlank()) {
                        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Text(
                                code.chunked(3).joinToString(" "),
                                Modifier.fillMaxWidth().padding(24.dp),
                                style = MaterialTheme.typography.displayMedium,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                        Text("Controlla che il codice sia uguale sui due schermi e conferma sulla TV.")
                        Text(
                            "Dopo la conferma il video partirà sulla TV e qui troverai il telecomando.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text("Collegamento all’app sulla TV…")
                    }
                } else {
                    Text("Scegli uno schermo sulla stessa rete Wi-Fi.", style = MaterialTheme.typography.bodyMedium)
                    if (state.discovering) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Column(
                        Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.devices.forEach { device ->
                            Surface(
                                onClick = { connect(device) },
                                enabled = !state.connecting,
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    Icon(
                                        if (device.protocol ==
                                            CastProtocol.GOOGLE_CAST
                                        ) {
                                            Icons.Default.Cast
                                        } else {
                                            Icons.Default.Tv
                                        },
                                        null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(device.name, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            device.protocol.label,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Icon(Icons.Default.ChevronRight, null)
                                }
                            }
                        }
                        if (state.devices.isEmpty()) {
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    Icons.Default.Tv,
                                    null,
                                    Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    if (state.discovering) "Cerchiamo gli schermi vicini…" else "Nessuna TV trovata",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                if (!state.discovering) {
                                    Text(
                                        "Apri Nyanime sulla TV, oppure attiva Google Cast o DLNA. Le reti ospiti possono impedire il collegamento.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                    TextButton(onClick = { manual = !manual }) { Text("Collega app TV") }
                    if (manual) {
                        Text(
                            "Apri “Collega telefono” nell’app Nyanime sulla TV e inserisci l’indirizzo mostrato.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            value = address,
                            onValueChange = { address = it.take(32) },
                            label = { Text("Indirizzo della TV") },
                            placeholder = { Text("192.168.1.10:38473") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(onClick = { connect() }, enabled = address.isNotBlank()) { Text("Collega") }
                    }
                    if (!state.googleAvailable) {
                        Text(
                            "Per Google Cast servono Google Play Services aggiornati.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    state.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = controller::discover, enabled = !state.discovering && !busy) {
                Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Cerca di nuovo")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (busy) "Annulla collegamento" else "Chiudi") } },
    )
}

@Composable
fun CastMiniController(modifier: Modifier = Modifier, includeNavigationInsets: Boolean = true) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val controller = remember { CastController.get(context) }
    val state by controller.state.collectAsState()
    if (!state.active && !state.connecting) return
    val enabled = state.active && !state.connecting && !state.needsReconnect
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(
            topStart = if (LocalNyanimeStyle.current) 8.dp else 20.dp,
            topEnd = if (LocalNyanimeStyle.current) 8.dp else 20.dp,
        ),
        color = if (LocalNyanimeStyle.current) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
    ) {
        Column(if (includeNavigationInsets) Modifier.navigationBarsPadding() else Modifier) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    Modifier.weight(1f)
                        .clickable(role = Role.Button, onClickLabel = "Apri telecomando") {
                            context.startActivity(Intent(context, CastRemoteActivity::class.java))
                        }
                        .semantics { contentDescription = "Apri il telecomando della TV" },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CastArtwork(state.media, Modifier.width(36.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.media?.title ?: "Collegamento alla TV…",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            state.error
                                ?: listOfNotNull(state.device?.name, state.media?.episodeName).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                FilledIconButton(onClick = controller::togglePause, enabled = enabled && !state.playback.finished) {
                    if (state.connecting || state.playback.buffering) {
                        CircularProgressIndicator(
                            Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = if (enabled) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    } else {
                        Icon(
                            if (state.playback.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            if (state.playback.paused) "Riprendi sulla TV" else "Pausa sulla TV",
                        )
                    }
                }
                if (state.media?.live == null) {
                    IconButton(onClick = controller::next, enabled = enabled && state.canNext) {
                        Icon(Icons.Default.SkipNext, "Prossimo episodio")
                    }
                }
            }
            if (state.media?.live == null && state.playback.durationMs > 0) {
                LinearProgressIndicator(
                    progress = { (state.playback.positionMs.toFloat() / state.playback.durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                )
            }
        }
    }
}

@Composable
fun CastRemoteScreen(onBack: () -> Unit, modifier: Modifier = Modifier, onQuality: (() -> Unit)? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val controller = remember { CastController.get(context) }
    val state by controller.state.collectAsState()
    var roomSheet by remember { mutableStateOf(false) }
    if (roomSheet) eu.kanade.tachiyomi.ui.watch.WatchTogetherSheet { roomSheet = false }
    CastRemoteContent(
        state = state,
        actions = CastRemoteActions(
            playEpisode = controller::playEpisode,
            pause = controller::togglePause,
            previous = controller::previous,
            next = controller::next,
            seek = controller::seek,
            volume = controller::setVolume,
            adjustVolume = controller::adjustVolume,
            brightness = controller::setBrightness,
            subtitle = controller::setSubtitle,
            reconnect = controller::reconnect,
            returnToPhone = {
                controller.stop(returnToPhone = true)
                onBack()
            },
            stop = {
                controller.stop()
                onBack()
            },
            browse = {
                onBack()
                context.startActivity(
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                )
            },
            back = onBack,
            quality = onQuality,
            room = if (state.media?.live != null) {
                {
                    val rooms = eu.kanade.tachiyomi.data.watch.WatchTogetherManager.get(context)
                    if (rooms.controller.active) {
                        roomSheet = true
                    } else {
                        state.media?.let { media ->
                            media.live?.let { live ->
                                rooms.roomForTv(nyanime.tv.api.TvChannel(live.reference, media.title), context)
                            }
                        }
                    }
                }
            } else {
                null
            },
        ),
        modifier = modifier,
    )
}

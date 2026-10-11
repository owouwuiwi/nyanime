package it.lorelore.nyanime.attention

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import eu.kanade.tachiyomi.ui.player.session.PlaybackSessions
import it.lorelore.nyanime.attention.core.AttentionAction
import it.lorelore.nyanime.attention.core.AttentionCamera
import it.lorelore.nyanime.attention.core.AttentionControl
import it.lorelore.nyanime.attention.core.AttentionMode
import it.lorelore.nyanime.attention.core.AttentionObservation
import it.lorelore.nyanime.attention.core.AttentionPlayer
import it.lorelore.nyanime.attention.core.AttentionPlayers
import it.lorelore.nyanime.attention.core.AttentionPolicy
import it.lorelore.nyanime.attention.core.AttentionReason
import it.lorelore.nyanime.attention.core.AttentionSignal
import it.lorelore.nyanime.attention.core.FaceClassifier
import it.lorelore.nyanime.attention.core.R
import it.lorelore.nyanime.attention.core.RecoveryMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import uy.kohesive.injekt.api.get
import java.util.concurrent.atomic.AtomicReference

/** Activity-visible owner only. An off preference never constructs a camera or detector. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttentionHost(activity: ComponentActivity) {
    val preferences = remember { AttentionPreferences() }
    val enabled by preferences.enabled.changes().collectAsState(preferences.enabled.get())
    val settings by AttentionUi.settings.collectAsState()
    if (enabled) EnabledAttentionHost(activity, preferences)
    if (settings) {
        ModalBottomSheet(
            onDismissRequest = AttentionUi::closeSettings,
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Text(
                stringResource(R.string.attention_title),
                Modifier.padding(horizontal = 20.dp),
                style = MaterialTheme.typography.headlineSmall,
            )
            AttentionSettingsContent()
        }
    }
}

@Composable
private fun EnabledAttentionHost(activity: ComponentActivity, preferences: AttentionPreferences) {
    val session by PlaybackSessions.current.collectAsState()
    val verifying by AttentionUi.verifying.collectAsState()
    if (session != null || verifying) {
        AttentionRuntime(activity, preferences)
    }
}

@Composable
private fun AttentionRuntime(activity: ComponentActivity, preferences: AttentionPreferences) {
    val options by remember(preferences) { preferences.changes() }.collectAsState(preferences.options())
    val currentOptions by rememberUpdatedState(options)
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateAsState()
    val configuration = LocalConfiguration.current
    val suspended by AttentionUi.suspended.collectAsState()
    val configuring by AttentionUi.configuring.collectAsState()
    val verifying by AttentionUi.verifying.collectAsState()
    val preview by AttentionUi.preview.collectAsState()
    val calibrationRequest by AttentionUi.calibrationRequests.collectAsState()
    val clearRequest by AttentionUi.clearRequests.collectAsState()
    val session by PlaybackSessions.current.collectAsState()
    val globalPrivate =
        remember { uy.kohesive.injekt.Injekt.get<eu.kanade.domain.base.BasePreferences>().incognitoMode() }
    val native = remember(session) { session?.let(::MpvAttentionPlayer) }
    val policy = remember { AttentionPolicy() }
    val classifier = remember { FaceClassifier() }
    val traceRecorder = if (options.mode == AttentionMode.ObserveOnly) {
        remember(preferences) { AttentionTraceRecorder(preferences) }
    } else {
        null
    }
    DisposableEffect(traceRecorder) {
        onDispose { traceRecorder?.close() }
    }
    var loadGuard by remember { mutableStateOf(it.lorelore.nyanime.attention.core.AttentionLoadGuard()) }
    val observation = remember { AtomicReference<AttentionObservation?>(null) }
    var camera by remember { mutableStateOf<AttentionCamera?>(null) }
    var needsCamera by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf<AttentionReason?>(null) }
    var mediaKey by remember { mutableStateOf<String?>(null) }
    var point by remember { mutableStateOf<RecoveryPoint?>(null) }
    var pointPrivate by remember { mutableStateOf(false) }
    var pointMode by remember { mutableStateOf(options.mode) }
    val resumed = lifecycle.isAtLeast(Lifecycle.State.RESUMED) && !activity.isInPictureInPictureMode
    val permitted =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    val canOperate = resumed && !suspended && permitted
    val activeCamera = remember { AtomicReference<Any?>(null) }

    fun frame() = native
        ?.let { player -> player.snapshot()?.let { player to it } }
    fun stableKey(key: String) = if (key.startsWith("online:")) AttentionPreferences.key(key) else key
    fun contextAllowed() =
        !eu.kanade.tachiyomi.data.cast.CastController.sessionActive.value &&
            !eu.kanade.tachiyomi.data.watch.WatchTogetherManager.isRoomActive()

    DisposableEffect(Unit) {
        AttentionControl.setActive(true)
        onDispose {
            AttentionControl.setActive(false)
            AttentionUi.recover = {}
            AttentionUi.status.value = AttentionStatus()
            AttentionUi.suspended.value = false
        }
    }
    LaunchedEffect(clearRequest) {
        if (clearRequest > 0) preferences.clear()
        point = null
    }
    LaunchedEffect(calibrationRequest) {
        if (calibrationRequest > 0) camera?.calibrate()
    }
    LaunchedEffect(resumed, suspended, permitted, native, configuring, verifying, options) {
        policy.reset()
        paused = null
        if (options.mode == AttentionMode.ObserveOnly) point = null
        observation.set(null)
        if (verifying) {
            failed = false
            loadGuard = it.lorelore.nyanime.attention.core.AttentionLoadGuard()
        }
        if (!resumed) {
            traceRecorder?.stop()
            needsCamera = false
            return@LaunchedEffect
        }
        while (isActive) {
            val pair = frame()
            val player = pair?.first
            val raw = pair?.second
            val snapshot = raw?.copy(
                key = stableKey(raw.key),
                incognito = raw.incognito || globalPrivate.get(),
                allowed =
                raw.allowed &&
                    canOperate &&
                    !configuring &&
                    contextAllowed(),
            )
            if (snapshot?.key != mediaKey ||
                (snapshot?.incognito ?: true) != pointPrivate ||
                pointMode != currentOptions.mode
            ) {
                if (mediaKey != null) classifier.reset()
                mediaKey = snapshot?.key
                loadGuard = it.lorelore.nyanime.attention.core.AttentionLoadGuard()
                policy.reset()
                observation.set(null)
                AttentionUi.suspended.value = false
                failed = false
                pointPrivate = snapshot?.incognito ?: true
                pointMode = currentOptions.mode
                point = snapshot?.takeIf { currentOptions.mode == AttentionMode.Automatic }
                    ?.let { preferences.point(it.key, it.incognito) }
            }
            val now = SystemClock.elapsedRealtime()
            traceRecorder?.tick(now, observation.get(), snapshot)
            if (snapshot != null && player != null) {
                loadGuard.sample(now, snapshot)
                camera?.minimumIntervalMs?.set(loadGuard.intervalMs)
                if (loadGuard.suspended && !verifying) {
                    policy.reset()
                    observation.set(null)
                }
                for (action in policy.tick(now, observation.get(), snapshot, currentOptions)) {
                    // Check again at dispatch: stale media and manual commands never inherit authority.
                    val live = player.snapshot() ?: continue
                    if (live.revision != snapshot.revision ||
                        raw.key != live.key ||
                        !snapshot.allowed ||
                        !live.allowed ||
                        !contextAllowed()
                    ) {
                        continue
                    }
                    AttentionControl.ownedCommand {
                        when (action) {
                            is AttentionAction.Remember -> {
                                preferences.save(snapshot.key, action.positionMs, snapshot.incognito)
                                point = preferences.point(snapshot.key, snapshot.incognito)
                            }
                            is AttentionAction.Pause -> {
                                action.recoveryMs?.let {
                                    preferences.save(snapshot.key, it, snapshot.incognito)
                                    point = preferences.point(snapshot.key, snapshot.incognito)
                                }
                                player.pause()
                            }
                            is AttentionAction.Resume -> {
                                if (currentOptions.recovery == RecoveryMode.Automatic && live.seekable) {
                                    point?.let {
                                        player.seek(it.positionMs)
                                        preferences.remove(snapshot.key, snapshot.incognito)
                                        point = null
                                    }
                                }
                                player.resume()
                            }
                            is AttentionAction.Recover -> {
                                if (!live.seekable) return@ownedCommand
                                player.seek(action.positionMs)
                                preferences.remove(snapshot.key, snapshot.incognito)
                                point = null
                            }
                        }
                    }
                }
                paused = policy.pauseReason
                needsCamera =
                    !failed &&
                    !loadGuard.suspended &&
                    canOperate &&
                    (verifying || policy.cameraNeeded(now, snapshot, currentOptions))
            } else {
                policy.reset()
                paused = null
                needsCamera = !failed && canOperate && verifying
            }
            val latest = observation.get()?.takeIf { now - it.atMs <= 1_000 }
            val label = when {
                !permitted -> R.string.attention_permission
                failed -> R.string.attention_camera_error
                loadGuard.suspended -> R.string.attention_load_suspended
                paused == AttentionReason.EyesClosed -> R.string.attention_paused_closed
                paused == AttentionReason.Distraction -> R.string.attention_paused_away
                !canOperate || snapshot?.allowed == false && !verifying -> R.string.attention_suspended
                !needsCamera -> R.string.attention_not_playing
                latest == null || latest.signal == AttentionSignal.Unknown -> R.string.attention_uncertain
                currentOptions.mode == AttentionMode.ObserveOnly -> R.string.attention_recording
                else -> R.string.attention_active
            }
            AttentionUi.status.value = AttentionStatus(
                label,
                mediaKey,
                point?.positionMs,
                camera?.calibrationRemaining?.get()?.let { it > 0 } == true,
                classifier.calibrated,
            )
            AttentionUi.recover = {
                val target = point
                val live = frame()
                if (currentOptions.mode == AttentionMode.Automatic &&
                    target != null &&
                    live != null &&
                    target.key == stableKey(live.second.key) &&
                    live.second.seekable &&
                    live.second.allowed &&
                    contextAllowed() &&
                    (live.second.incognito || globalPrivate.get()) == pointPrivate
                ) {
                    val resume = policy.pauseReason != null && live.second.revision == snapshot?.revision
                    live.first.seek(target.positionMs)
                    if (resume) live.first.resume()
                    preferences.remove(target.key, pointPrivate)
                    point = null
                }
            }
            // Foreground coordination only. Inference has its own single, bounded background executor.
            delay(if (needsCamera || paused != null) 200 else 750)
        }
    }

    // Reading configuration makes display-rotation changes re-evaluate the camera binding.
    val rotation = remember(configuration) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            activity.display?.rotation ?: android.view.Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.rotation
        }
    }
    DisposableEffect(needsCamera, resumed, mediaKey, rotation, preview, options.sensitivity) {
        if (!needsCamera || !resumed) return@DisposableEffect onDispose { }
        observation.set(null)
        val token = Any()
        activeCamera.set(token)
        val current = AttentionCamera(
            activity,
            owner,
            rotation,
            options.sensitivity,
            classifier,
            preview = if (verifying) preview else null,
            onObservation = { if (activeCamera.get() === token) observation.set(it) },
            onFailure = {
                failed = true
                observation.set(null)
                policy.reset()
                paused = null
                needsCamera = false
            },
        )
        camera = current
        current.start()
        onDispose {
            activeCamera.compareAndSet(token, null)
            current.close()
            if (camera === current) camera = null
            observation.set(null)
        }
    }
    val status by AttentionUi.status.collectAsState()
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        AttentionReveal(
            paused != null && canOperate && !configuring,
            modifier = Modifier.align(Alignment.Center),
        ) {
            Card(Modifier.widthIn(max = 360.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(status.label), style = MaterialTheme.typography.titleMedium)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (point != null) {
                            TextButton(onClick = { AttentionUi.recover() }) {
                                Text(stringResource(R.string.attention_recover))
                            }
                        }
                        Button(onClick = {
                            val live = frame()
                            if (live != null &&
                                stableKey(live.second.key) == mediaKey &&
                                live.second.allowed &&
                                contextAllowed()
                            ) {
                                live.first.resume()
                            }
                        }) { Text(stringResource(R.string.attention_resume)) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AttentionVerification(modifier: Modifier) {
    AndroidView(
        factory = { context ->
            PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
                AttentionUi.preview.value = surfaceProvider
            }
        },
        modifier = modifier.clip(MaterialTheme.shapes.large),
        onRelease = { view ->
            if (AttentionUi.preview.value ===
                view.surfaceProvider
            ) {
                AttentionUi.preview.value = null
            }
        },
    )
}

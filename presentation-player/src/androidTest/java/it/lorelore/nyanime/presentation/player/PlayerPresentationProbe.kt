// Copyright (c) 2026 owouwuiwi and Nyanime contributors.
// SPDX-License-Identifier: Apache-2.0
// Original Nyanime implementation; see this module's LICENSE.

package it.lorelore.nyanime.presentation.player

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.motionEventSpy
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import java.io.File

/** Isolated renderer proof: no host app, network, media, account or user library. */
private object ProbeScreen {
    var playing by mutableStateOf(true)
    var settings by mutableStateOf(false)
    var light by mutableStateOf(false)
    var largeText by mutableStateOf(false)
    var volume by mutableStateOf(false)
    var fullscreen by mutableStateOf(true)
    var position by mutableFloatStateOf(90_000f)
    val events = mutableListOf<String>()
    val motionEvents = mutableListOf<Int>()
    var backgroundTaps = 0
    var backgroundDrags = 0
    var activity: PlayerPresentationProbeActivity? = null
}

@OptIn(ExperimentalComposeUiApi::class)
class PlayerPresentationProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProbeScreen.activity = this
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        setContent {
            val dark = darkColorScheme(primary = Color(0xFFFF284F))
            val light = lightColorScheme(primary = Color(0xFFD95F00))
            MaterialTheme(colorScheme = if (ProbeScreen.light) light else dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, if (ProbeScreen.largeText) 1.4f else 1f),
                ) {
                    Box(
                        Modifier.fillMaxSize().background(Color(0xFF172026)).motionEventSpy {
                            ProbeScreen.motionEvents += it.actionMasked
                        },
                    ) {
                        // Match the host's gesture sibling below its controls. A
                        // full-screen pointer observer above it must fail this probe,
                        // even if that observer never consumes an event explicitly.
                        Box(
                            Modifier.fillMaxSize().pointerInput(Unit) {
                                detectTapGestures { ProbeScreen.backgroundTaps++ }
                            }.pointerInput(Unit) {
                                detectDragGestures { change, _ ->
                                    change.consume()
                                    ProbeScreen.backgroundDrags++
                                }
                            },
                        )
                        VideoPlaybackChrome(
                            state = VideoChromeState(
                                true, ProbeScreen.playing, false, "Sample video", "Episode 1", 1_200_000L,
                                fullscreen = ProbeScreen.fullscreen, previous = false, next = true,
                                subtitles = true, pip = true, queue = true,
                                chapters = listOf(
                                    VideoChromeChapter("Part 1", 0),
                                    VideoChromeChapter("Part 2", 600_000),
                                ),
                            ),
                            actions = VideoChromeActions(
                                playPause = { ProbeScreen.playing = !ProbeScreen.playing },
                                back = { ProbeScreen.fullscreen = false },
                                settings = { ProbeScreen.settings = true },
                                fullscreen = { ProbeScreen.fullscreen = !ProbeScreen.fullscreen },
                                seek = {
                                    ProbeScreen.events += "seek:$it"
                                    ProbeScreen.position = it.toFloat()
                                },
                                preview = { ProbeScreen.events += "preview:$it" },
                                scrubbing = { ProbeScreen.events += "drag:$it" },
                            ),
                            position = { ProbeScreen.position.toLong() },
                            buffered = .72f,
                            extraActions = {
                                VideoChromeButton("SM", { ProbeScreen.volume = !ProbeScreen.volume })
                                VideoChromeButton("Attention", {}, Icons.Filled.VisibilityOff)
                                VideoChromeButton("Room", {}, Icons.Filled.Groups)
                            },
                        )
                        VideoGestureFeedback(0, null, ProbeScreen.volume, false, { 1.6f }, { .5f }, false)
                        if (ProbeScreen.settings) {
                            VideoPlayerSheet({ ProbeScreen.settings = false }, title = "Playback settings") {
                                VideoSettingsGroup(
                                    "Video",
                                    listOf(
                                        VideoSettingsAction("Quality", Icons.Filled.HighQuality, "1080p", {}),
                                        VideoSettingsAction("Subtitles", Icons.Filled.Subtitles, "Off", {}),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

class PlayerPresentationProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            // Class definition absence establishes that the test renderer has no Flow runtime.
            check(runCatching { Class.forName("io.github.aedev.flow.player.EnhancedPlayerManager") }.isFailure)
            val activity =
                startActivitySync(
                    Intent(
                        targetContext,
                        PlayerPresentationProbeActivity::class.java,
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            pause()
            val frame = IntArray(4)
            runOnMainSync {
                activity.window.decorView.getLocationOnScreen(frame)
                frame[2] = activity.window.decorView.width
                frame[3] = activity.window.decorView.height
                ProbeScreen.backgroundTaps = 0
                ProbeScreen.backgroundDrags = 0
            }
            val blankY = frame[1] + frame[3] * .28f
            tap(frame[0] + frame[2] * .5f, blankY)
            pause()
            check(ProbeScreen.backgroundTaps == 1) { "Chrome blocked a tap outside controls" }
            drag(Rect(frame[0], blankY.toInt() - 1, frame[0] + frame[2], blankY.toInt() + 1), cancel = false)
            pause()
            check(ProbeScreen.backgroundDrags > 0) { "Chrome blocked the gesture sibling's drag" }
            click(label(R.string.player_ui_pause))
            pause()
            check(!ProbeScreen.playing)
            capture("renderer-fullscreen-dark.png")
            click(label(R.string.player_ui_play))
            pause()
            check(ProbeScreen.playing)
            val bounds = Rect().also { requireNotNull(find(label(R.string.player_ui_seek))).getBoundsInScreen(it) }
            runOnMainSync {
                ProbeScreen.events.clear()
                ProbeScreen.motionEvents.clear()
            }
            drag(bounds, cancel = true)
            pause()
            check(MotionEvent.ACTION_UP !in ProbeScreen.motionEvents) {
                "Test cancellation was converted to a release: ${ProbeScreen.motionEvents}; ${ProbeScreen.events}"
            }
            check(
                ProbeScreen.events.none {
                    it.startsWith("seek:")
                },
            ) { "Cancelled drag committed a seek: ${ProbeScreen.events}; raw: ${ProbeScreen.motionEvents}" }
            check(ProbeScreen.events.count { it == "drag:true" } == 1)
            check(ProbeScreen.events.lastOrNull() == "drag:false")
            runOnMainSync { ProbeScreen.events.clear() }
            drag(bounds, cancel = false)
            pause()
            check(
                ProbeScreen.events.count {
                    it.startsWith("seek:")
                } == 1,
            ) { "Drag must commit once: ${ProbeScreen.events}" }
            check(ProbeScreen.events.lastOrNull() == "drag:false")
            runOnMainSync { ProbeScreen.largeText = true }
            pause()
            capture("renderer-fullscreen-large-text.png")
            runOnMainSync {
                ProbeScreen.light = true
                ProbeScreen.volume = true
            }
            pause()
            capture("renderer-light-volume.png")
            runOnMainSync {
                ProbeScreen.volume = false
                ProbeScreen.settings = true
            }
            pause()
            check(find("Playback settings") != null)
            capture("renderer-settings.png")
            runOnMainSync {
                ProbeScreen.settings = false
                ProbeScreen.playing = false
                activity.finish()
            }
            result.putString(
                "stream",
                "PLAYER_PRESENTATION_DEVICE_OK: standalone, background touch passthrough, play/pause, single seek, cancelled seek, large text, themes, gesture level, settings\n",
            )
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            runOnMainSync { ProbeScreen.activity?.finish() }
            result.putString("stream", "PLAYER_PRESENTATION_DEVICE_FAILED: ${failure.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun label(id: Int) = targetContext.getString(id)
    private fun pause() {
        Thread.sleep(800)
        waitForIdleSync()
    }

    private fun find(label: String): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let {
                    visit(it)?.let { match -> return match }
                }
            }
            return null
        }
        return uiAutomation.rootInActiveWindow?.let(::visit)
    }

    private fun click(label: String) {
        val node = requireNotNull(find(label)) { "Missing control: $label" }
        // Material places the spoken icon label below its clickable button node.
        // Exercise the accessible owner, rather than sending ACTION_CLICK to text.
        val button = generateSequence(node) { it.parent }.firstOrNull { it.isClickable && it.isEnabled }
        checkNotNull(button) { "Unclickable control: $label" }
        check(button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Click failed: $label" }
    }

    private fun tap(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            try {
                check(uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
            Thread.sleep(50)
        }
    }

    private fun capture(name: String) {
        val path = File(targetContext.getExternalFilesDir(null), name)
        checkNotNull(uiAutomation.takeScreenshot()).let { bitmap ->
            path.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun drag(bounds: Rect, cancel: Boolean) {
        val start = SystemClock.uptimeMillis()
        val y = bounds.centerY().toFloat()
        val x = bounds.left + bounds.width() * .35f
        fun send(action: Int, fraction: Float) {
            val event = MotionEvent.obtain(
                start,
                SystemClock.uptimeMillis(),
                action,
                x + bounds.width() * fraction,
                y,
                0,
            ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try {
                check(uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, 0f)
        Thread.sleep(100)
        send(MotionEvent.ACTION_MOVE, .1f)
        Thread.sleep(100)
        send(MotionEvent.ACTION_MOVE, .25f)
        Thread.sleep(100)
        send(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, .25f)
    }
}

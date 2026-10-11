// Copyright (c) 2026 owouwuiwi and Nyanime contributors.
// SPDX-License-Identifier: Apache-2.0
// Original Nyanime implementation; see this module's LICENSE.

package it.lorelore.nyanime.presentation.player

import androidx.compose.runtime.Immutable

/** UI values only. Engines, media catalogues and storage remain in their owners. */
@Immutable
data class VideoChromeState(
    val visible: Boolean,
    val playing: Boolean,
    val buffering: Boolean,
    val title: String,
    val subtitle: String,
    val durationMs: Long,
    val speed: Float = 1f,
    val quality: String? = null,
    val resizeMode: Int = 0,
    val fullscreen: Boolean = false,
    val portraitFullscreen: Boolean = false,
    val live: Boolean = false,
    val previous: Boolean = false,
    val next: Boolean = false,
    val subtitles: Boolean = false,
    val pip: Boolean = false,
    val queue: Boolean = false,
    val autoplay: Boolean = false,
    val sleepTimer: Boolean = false,
    val locked: Boolean = false,
    val showRemainingTime: Boolean = false,
    val gestureActive: Boolean = false,
    val chapters: List<VideoChromeChapter> = emptyList(),
)

@Immutable
data class VideoChromeChapter(val title: String, val positionMs: Long)

data class VideoChromeActions(
    val playPause: () -> Unit,
    val back: () -> Unit,
    val settings: () -> Unit,
    val fullscreen: () -> Unit,
    val seek: (Long) -> Unit = {},
    val preview: (Long) -> Unit = {},
    val scrubbing: (Boolean) -> Unit = {},
    val previous: () -> Unit = {},
    val next: () -> Unit = {},
    val quality: () -> Unit = {},
    val speed: () -> Unit = {},
    val subtitles: () -> Unit = {},
    val subtitleSettings: () -> Unit = {},
    val pip: () -> Unit = {},
    val resize: () -> Unit = {},
    val queue: () -> Unit = {},
    val chapters: () -> Unit = {},
    val cast: () -> Unit = {},
    val sleepTimer: () -> Unit = {},
    val lock: () -> Unit = {},
    val autoplay: (Boolean) -> Unit = {},
    val toggleRemainingTime: () -> Unit = {},
    val live: () -> Unit = {},
    val interaction: () -> Unit = {},
)

/** One UI drag owns one preview. Cancellation never commits a seek. */
internal class TimelinePreview {
    var targetMs: Long? = null
        private set
    private var pointerSequence = false
    private var pointerReleased = false

    fun beginPointer() {
        pointerSequence = true
        pointerReleased = false
    }

    fun releasePointer() {
        if (pointerSequence) pointerReleased = true
    }

    fun update(fraction: Float, durationMs: Long, actions: VideoChromeActions) {
        if (durationMs <= 0 || !fraction.isFinite()) return
        if (targetMs == null) actions.scrubbing(true)
        val target = (fraction.coerceIn(0f, 1f).toDouble() * durationMs).toLong().coerceIn(0, durationMs)
        targetMs = target
        actions.preview(target)
    }

    fun finish(actions: VideoChromeActions) {
        // A slider also calls "finished" when Android cancels its pointer stream.
        // Touch commits need a real release; keyboard/accessibility have no stream.
        if (pointerSequence && !pointerReleased) {
            cancel(actions)
            return
        }
        pointerSequence = false
        pointerReleased = false
        val target = targetMs ?: return
        targetMs = null
        try {
            actions.seek(target)
        } finally {
            actions.scrubbing(false)
        }
    }

    fun cancel(actions: VideoChromeActions) {
        pointerSequence = false
        pointerReleased = false
        if (targetMs == null) return
        targetMs = null
        actions.scrubbing(false)
    }
}

internal fun playbackFraction(positionMs: Long, durationMs: Long): Float =
    if (durationMs <= 0) 0f else (positionMs.toDouble() / durationMs).toFloat().coerceIn(0f, 1f)

internal fun playbackTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1000L
    return if (seconds >= 3600L) {
        "%d:%02d:%02d".format(java.util.Locale.ROOT, seconds / 3600L, seconds / 60L % 60L, seconds % 60L)
    } else {
        "%02d:%02d".format(java.util.Locale.ROOT, seconds / 60L, seconds % 60L)
    }
}

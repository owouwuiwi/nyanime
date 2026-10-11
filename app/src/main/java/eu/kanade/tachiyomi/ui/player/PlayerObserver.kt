package eu.kanade.tachiyomi.ui.player

import `is`.xyz.mpv.MPVLib
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

class PlayerObserver(val activity: PlayerSession) :
    MPVLib.EventObserver,
    MPVLib.LogObserver {

    override fun eventProperty(property: String) {
        val generation = activity.playbackGeneration
        activity.runOnUiThread {
            if (activity.playbackGeneration ==
                generation
            ) {
                activity.onObserverEvent(property)
                activity.publishSnapshot()
            }
        }
    }

    override fun eventProperty(property: String, value: Long) {
        val generation = activity.playbackGeneration
        activity.runOnUiThread {
            if (activity.playbackGeneration ==
                generation
            ) {
                activity.onObserverEvent(property, value)
                activity.publishSnapshot()
            }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        val generation = activity.playbackGeneration
        activity.runOnUiThread {
            if (activity.playbackGeneration ==
                generation
            ) {
                activity.onObserverEvent(property, value)
                activity.publishSnapshot()
            }
        }
    }

    override fun eventProperty(property: String, value: String) {
        val generation = activity.playbackGeneration
        activity.runOnUiThread {
            if (activity.playbackGeneration ==
                generation
            ) {
                activity.onObserverEvent(property, value)
                activity.publishSnapshot()
            }
        }
    }

    override fun eventProperty(property: String, value: Double) {
        val generation = activity.playbackGeneration
        activity.runOnUiThread {
            if (activity.playbackGeneration ==
                generation
            ) {
                activity.onObserverEvent(property, value)
                activity.publishSnapshot()
            }
        }
    }

    override fun event(eventId: Int) {
        val generation = activity.playbackGeneration
        activity.runOnUiThread {
            if (activity.playbackGeneration ==
                generation
            ) {
                activity.event(eventId)
                activity.publishSnapshot()
            }
        }
    }

    override fun efEvent(err: String?) {
        var errorMessage = err ?: "Error: File ended"
        if (!httpError.isNullOrEmpty()) {
            errorMessage += ": $httpError"
            httpError = null
        }
        logcat(LogPriority.ERROR) { errorMessage }
        val generation = activity.playbackGeneration
        activity.runOnUiThread {
            if (activity.playbackGeneration == generation) {
                activity.onPlaybackFailure(PlaybackFailure.fromNative(errorMessage))
            }
        }
    }

    private var httpError: String? = null

    fun clearError() {
        httpError = null
    }

    override fun logMessage(prefix: String, level: Int, text: String) {
        val logPriority = when (level) {
            MPVLib.mpvLogLevel.MPV_LOG_LEVEL_FATAL, MPVLib.mpvLogLevel.MPV_LOG_LEVEL_ERROR -> LogPriority.ERROR
            MPVLib.mpvLogLevel.MPV_LOG_LEVEL_WARN -> LogPriority.WARN
            MPVLib.mpvLogLevel.MPV_LOG_LEVEL_INFO -> LogPriority.INFO
            else -> LogPriority.VERBOSE
        }
        if (text.contains("HTTP error")) httpError = text
        if (logPriority == LogPriority.ERROR && prefix.contains("headphone")) {
            val generation = activity.playbackGeneration
            activity.runOnUiThread {
                if (activity.playbackGeneration == generation) activity.headphoneCinema.nativeError()
            }
        }
        if ((logPriority == LogPriority.ERROR || logPriority == LogPriority.WARN) && Anime4K.isShaderError(text)) {
            activity.runOnUiThread {
                if (activity.live.value == null) activity.onAnime4KShaderError(text)
            }
        }
        logcat.logcat("mpv/$prefix", logPriority) { text }
    }
}

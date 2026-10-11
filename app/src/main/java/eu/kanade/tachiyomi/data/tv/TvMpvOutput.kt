package eu.kanade.tachiyomi.data.tv

import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import logcat.LogPriority
import nyanime.tv.api.TvRules
import nyanime.tv.api.TvStream
import tachiyomi.core.common.util.system.logcat

data class TvNativeTrack(val id: Int, val type: String, val label: String)

data class TvNativeStatus(
    val position: Double? = null,
    val aspect: Double? = null,
    val volume: Double = 100.0,
    val tracks: List<TvNativeTrack> = emptyList(),
    val audio: String = "auto",
    val subtitle: String = "auto",
    val speed: Double = 1.0,
    val rewindStart: Double? = null,
    val rewindEnd: Double? = null,
)

interface TvNativeEvents {
    fun loaded(ticket: Long)
    fun buffering(ticket: Long, value: Boolean)
    fun failed(ticket: Long, failure: TvPlaybackFailure)
}

/**
 * Uses the existing activity's native instance. It never creates a second MPV instance.
 * The activity configures audio/decoder/TLS preferences before calling attach().
 */
class TvMpvOutput(
    private val scope: CoroutineScope,
    private val events: TvNativeEvents,
    private val applyEnhancement: (Boolean) -> Unit,
    private val destroyNative: () -> Unit,
) : TvNativeOutput, MPVLib.EventObserver, MPVLib.LogObserver {
    @Volatile private var alive = false
    private var attached = false
    private var receivedIdle = false
    private var currentStream: TvStream? = null
    private var rangeSecond = Long.MIN_VALUE

    @Volatile private var loadFailure: Pair<Long, TvPlaybackFailure>? = null
    private val mutableStatus = MutableStateFlow(TvNativeStatus())
    val status = mutableStatus.asStateFlow()
    private val lane = TvLoadLane(
        stop = { if (alive) MPVLib.command(arrayOf("stop")) },
        load = { stream, _ -> loadNative(stream) },
    )

    /** One observed idle notification establishes the initial load boundary. */
    fun attach() {
        check(!attached)
        // BaseMPVView selects idle=once. A live session must survive stop/idle
        // boundaries so channel changes and reconnects can reuse the same owner.
        check(MPVLib.setOptionString("idle", "yes") >= 0)
        attached = true
        alive = true
        MPVLib.addObserver(this)
        MPVLib.addLogObserver(this)
        MPVLib.observeProperty("idle-active", MPVLib.mpvFormat.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("paused-for-cache", MPVLib.mpvFormat.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("eof-reached", MPVLib.mpvFormat.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("time-pos", MPVLib.mpvFormat.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("video-params/aspect", MPVLib.mpvFormat.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("volume", MPVLib.mpvFormat.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("speed", MPVLib.mpvFormat.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("track-list/count", MPVLib.mpvFormat.MPV_FORMAT_INT64)
        MPVLib.observeProperty("aid", MPVLib.mpvFormat.MPV_FORMAT_STRING)
        MPVLib.observeProperty("sid", MPVLib.mpvFormat.MPV_FORMAT_STRING)
    }

    override fun load(stream: TvStream, ticket: Long) {
        check(alive)
        require(TvRules.valid(stream))
        lane.request(stream, ticket)
    }

    private fun loadNative(stream: TvStream) {
        if (!alive) return
        currentStream = stream
        loadFailure = null
        rangeSecond = Long.MIN_VALUE
        mutableStatus.value = TvNativeStatus(volume = mutableStatus.value.volume)
        MPVLib.setPropertyBoolean("pause", true)
        MPVLib.setOptionString("start", "none")
        MPVLib.setOptionString("length", "none")
        MPVLib.setPropertyBoolean("keep-open", false)
        MPVLib.setPropertyDouble("speed", 1.0)
        MPVLib.setOptionString(
            "http-header-fields",
            stream.headers.entries.joinToString(",") { (key, value) ->
                "$key: ${value.replace(",", "\\,")}"
            },
        )
        MPVLib.command(arrayOf("loadfile", stream.url, "replace"))
    }

    override fun pause(value: Boolean) {
        if (alive) MPVLib.setPropertyBoolean("pause", value)
    }
    override fun speed(value: Double) {
        if (alive) MPVLib.setPropertyDouble("speed", value)
    }
    override fun seek(
        seconds: Double,
    ) {
        if (alive) MPVLib.command(arrayOf("seek", seconds.toString(), "absolute+exact"))
    }
    override fun enhancement(enabled: Boolean) {
        if (alive) applyEnhancement(enabled)
    }
    override fun seekWindow(): ClosedFloatingPointRange<Double>? {
        val status = status.value
        val start = status.rewindStart ?: return null
        val end = status.rewindEnd ?: return null
        return (start..end).takeIf { alive && end > start }
    }

    fun track(type: String, id: String) {
        if (!alive || type !in setOf("audio", "sub")) return
        if (id !in setOf("auto", "no") && status.value.tracks.none { it.type == type && it.id.toString() == id }) return
        MPVLib.setPropertyString(if (type == "audio") "aid" else "sid", id)
    }

    private fun refreshTracks() {
        val tracks = (0 until (MPVLib.getPropertyInt("track-list/count") ?: 0).coerceIn(0, 128)).mapNotNull { index ->
            val type = MPVLib.getPropertyString("track-list/$index/type") ?: return@mapNotNull null
            if (type !in setOf("audio", "sub")) return@mapNotNull null
            val id = MPVLib.getPropertyInt("track-list/$index/id") ?: return@mapNotNull null
            val label = listOfNotNull(
                MPVLib.getPropertyString("track-list/$index/title"),
                MPVLib.getPropertyString("track-list/$index/lang"),
            ).filter {
                it.isNotBlank()
            }.distinct().joinToString(" · ")
            TvNativeTrack(id, type, label.ifBlank { id.toString() })
        }
        mutableStatus.value = mutableStatus.value.copy(tracks = tracks)
    }

    private fun refreshRewind(position: Double) {
        if (position.toLong() == rangeSecond) return
        rangeSecond = position.toLong()
        val range = if (currentStream?.rewind == true && MPVLib.getPropertyBoolean("seekable") == true) {
            // This is a native NODE map, not a property list with /count paths.
            TvSeekWindow.read(MPVLib.getPropertyString("demuxer-cache-state"), position)
        } else {
            null
        }
        mutableStatus.value = mutableStatus.value.copy(rewindStart = range?.start, rewindEnd = range?.endInclusive)
    }

    fun volume(value: Double) {
        if (alive && value.isFinite()) MPVLib.setPropertyDouble("volume", value.coerceIn(0.0, 100.0))
    }

    override fun release() {
        if (!alive) return
        // Shut the callback gate before observer removal and destruction, including queued events.
        alive = false
        lane.close()
        currentStream = null
        MPVLib.removeObserver(this)
        MPVLib.removeLogObserver(this)
        destroyNative()
    }

    private fun post(block: () -> Unit) {
        if (!alive) return
        scope.launch(Dispatchers.Main.immediate) {
            if (!alive) return@launch
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                val ticket = lane.eventTicket
                // A failed load may leave MPV idle without another idle-change notification.
                val idle = runCatching { MPVLib.getPropertyBoolean("idle-active") }.getOrNull()
                if (idle != null) lane.idle(idle)
                ticket?.let { events.failed(it, failureFor(it)) }
            }
        }
    }

    override fun eventProperty(property: String) = Unit
    override fun eventProperty(property: String, value: Long) {
        if (property == "track-list/count") post { refreshTracks() }
    }
    override fun eventProperty(property: String, value: String) {
        if (property == "aid" || property == "sid") {
            post {
                mutableStatus.value =
                    if (property == "aid") status.value.copy(audio = value) else status.value.copy(subtitle = value)
            }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        val ticket = lane.eventTicket // Capture on the native event thread, never after dispatch.
        post {
            when (property) {
                "idle-active" -> {
                    if (!receivedIdle) {
                        receivedIdle = true
                        lane.initialized(value)
                    } else {
                        lane.idle(value)
                    }
                }
                "paused-for-cache" -> ticket?.let { events.buffering(it, value) }
                "eof-reached" -> if (value) ticket?.let { events.failed(it, failureFor(it)) }
            }
        }
    }

    override fun eventProperty(property: String, value: Double) {
        val ticket = lane.eventTicket
        post {
            if (ticket == null || ticket != lane.eventTicket || !value.isFinite()) return@post
            when (property) {
                "time-pos" -> {
                    mutableStatus.value = mutableStatus.value.copy(position = value.takeIf { it >= 0 })
                    refreshRewind(value)
                }
                "video-params/aspect" ->
                    mutableStatus.value =
                        mutableStatus.value.copy(aspect = value.takeIf { it > 0 })
                "volume" -> mutableStatus.value = mutableStatus.value.copy(volume = value.coerceIn(0.0, 100.0))
                "speed" -> mutableStatus.value = mutableStatus.value.copy(speed = value)
            }
        }
    }

    override fun event(eventId: Int) {
        val ticket = lane.eventTicket
        post {
            if (ticket == null || ticket != lane.eventTicket) return@post
            when (eventId) {
                MPVLib.mpvEventId.MPV_EVENT_FILE_LOADED -> {
                    try {
                        currentStream?.let { stream ->
                            stream.audio.forEach { MPVLib.command(arrayOf("audio-add", it.url, "auto", it.label)) }
                            stream.subtitles.forEach { MPVLib.command(arrayOf("sub-add", it.url, "auto", it.label)) }
                        }
                        refreshTracks()
                        events.loaded(ticket)
                    } catch (_: Exception) {
                        events.failed(ticket, failureFor(ticket))
                    }
                }
                MPVLib.mpvEventId.MPV_EVENT_PLAYBACK_RESTART -> events.buffering(ticket, false)
            }
        }
    }

    override fun efEvent(err: String?) {
        val ticket = lane.eventTicket
        val failure = ticket?.let { failureFor(it, err) }
        post {
            if (ticket != null && ticket == lane.eventTicket) {
                logcat(LogPriority.WARN) { "Live load $ticket failed: $failure" }
                events.failed(ticket, failure ?: TvPlaybackFailure.UNKNOWN)
            }
        }
    }

    private fun failureFor(ticket: Long, message: String? = null): TvPlaybackFailure =
        loadFailure?.takeIf { it.first == ticket }?.second
            ?: TvPlaybackFailure.classify(message) ?: TvPlaybackFailure.UNKNOWN

    override fun logMessage(prefix: String, level: Int, text: String) {
        if (!alive || level > MPVLib.mpvLogLevel.MPV_LOG_LEVEL_WARN) return
        val ticket = lane.eventTicket ?: return
        val failure = TvPlaybackFailure.classify(text) ?: return
        // No source identity, path, stream URL, cookie or authorization value reaches Logcat.
        loadFailure = ticket to failure
    }
}

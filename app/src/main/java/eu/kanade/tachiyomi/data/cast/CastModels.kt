package eu.kanade.tachiyomi.data.cast

import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.domain.entries.anime.model.AnimeCover

data class CastRequest(
    val animeId: Long,
    val episodeId: Long,
    val sourceId: Long,
    val title: String,
    val episodeName: String,
    val video: Video,
    val positionMs: Long,
    val durationMs: Long,
    val playlist: List<Long>,
    val autoPlay: Boolean,
)

enum class CastProtocol(val label: String) {
    GOOGLE_CAST(
        "Google Cast",
    ),
    DLNA("DLNA / UPnP"),
    COMPANION("App Nyanime TV"),
}

data class CastDevice(val id: String, val name: String, val protocol: CastProtocol)

data class CastSubtitle(val name: String, val url: String)

data class TvCastRequest(val reference: nyanime.tv.api.TvChannelRef, val qualityId: String? = null)

data class TvCastContent(val reference: nyanime.tv.api.TvChannelRef, val rewind: Boolean = false)

data class CastAudioStream(val url: String, val mimeType: String, val headers: Map<String, String>)

/** Provider-neutral audio handoff; no fabricated episodes or extension identifiers. */
data class CastAudioRequest(
    val identity: String,
    val title: String,
    val artist: String,
    val artwork: String?,
    val positionMs: Long,
    val durationMs: Long,
    val valid: () -> Boolean,
    val resolve: suspend () -> CastAudioStream,
    val committed: () -> Unit,
    val closed: () -> Unit,
    val resume: (Long) -> Unit,
    val progress: (Long) -> Unit,
    val next: (suspend () -> CastAudioRequest?)? = null,
    val previous: (suspend () -> CastAudioRequest?)? = null,
)

data class CastMedia(
    val animeId: Long?,
    val episodeId: Long?,
    val sourceId: Long,
    val title: String,
    val episodeName: String,
    val url: String,
    val mimeType: String,
    val startMs: Long,
    val durationMs: Long,
    val subtitles: List<CastSubtitle> = emptyList(),
    val cover: AnimeCover? = null,
    val quality: String = "",
    val live: TvCastContent? = null,
    val audio: Boolean = false,
    val artworkUrl: String? = null,
)

data class CastPlayback(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val paused: Boolean = true,
    val buffering: Boolean = false,
    val finished: Boolean = false,
    val canSeek: Boolean = true,
    val canSetVolume: Boolean = false,
    val volume: Float = 1f,
    val canSetBrightness: Boolean = false,
    val brightness: Float = 0.5f,
    val disconnected: Boolean = false,
    val remoteError: String? = null,
    val canSetSubtitles: Boolean = false,
)

data class CastState(
    val devices: List<CastDevice> = emptyList(),
    val discovering: Boolean = false,
    val connecting: Boolean = false,
    val device: CastDevice? = null,
    val media: CastMedia? = null,
    val playback: CastPlayback = CastPlayback(),
    val subtitleIndex: Int = -1,
    val error: String? = null,
    val googleAvailable: Boolean = true,
    val canNext: Boolean = false,
    val canPrevious: Boolean = false,
    val needsReconnect: Boolean = false,
    val skippedFillers: List<Long> = emptyList(),
) {
    val active: Boolean get() = device != null && media != null
}

/** Receivers expose no verified broadcast clock. Their pause and buffering remain local. */
internal fun CastState.liveObservation(reference: nyanime.tv.api.TvChannelRef?): nyanime.tv.api.TvLiveObservation? {
    if (!active || reference == null || media?.live?.reference != reference) return null
    return nyanime.tv.api.TvLiveObservation(
        positionSeconds = playback.positionMs.coerceAtLeast(0) / 1000.0,
        clock = null,
        paused = playback.paused,
        buffering = connecting || playback.buffering || needsReconnect,
        canChangeSpeed = false,
    )
}

interface CastTransport {
    val devices: StateFlow<List<CastDevice>>
    suspend fun discover()
    fun stopDiscovery()
    suspend fun load(device: CastDevice, media: CastMedia)
    suspend fun status(): CastPlayback
    suspend fun pause(paused: Boolean)
    suspend fun seek(positionMs: Long)
    suspend fun volume(value: Float)
    suspend fun brightness(value: Float) {
        error("La TV non espone il controllo della luminosità")
    }
    suspend fun subtitle(index: Int)
    suspend fun stop()
}

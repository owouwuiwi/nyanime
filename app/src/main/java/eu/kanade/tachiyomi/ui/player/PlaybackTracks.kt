package eu.kanade.tachiyomi.ui.player

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal enum class PlaybackTrackKind(val nativeType: String) { Audio("audio"), Subtitle("sub") }

@Serializable
internal data class NativePlaybackTrack(
    val id: Int = -1,
    val type: String = "",
    val title: String = "",
    val lang: String? = null,
    @SerialName("external-filename") val externalFilename: String? = null,
)

internal data class ExternalPlaybackTrack(val url: String, val title: String)

internal data class PlaybackTrackChoice(val id: Int, val title: String, val language: String?)

/** Native metadata is read atomically, rather than querying three JNI properties for every row. */
internal object PlaybackTrackSnapshot {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(value: String?): List<NativePlaybackTrack>? = value?.let {
        runCatching { json.decodeFromString<List<NativePlaybackTrack>>(it) }
            .getOrNull()?.filter { track -> track.id >= 0 }
    }
}

/** Alternatives are metadata until selected. Merely opening the menu never opens their URLs. */
internal class PlaybackTrackCatalog(
    audio: List<ExternalPlaybackTrack> = emptyList(),
    subtitles: List<ExternalPlaybackTrack> = emptyList(),
) {
    private val external = mapOf(
        PlaybackTrackKind.Audio to audio.distinctBy { it.url },
        PlaybackTrackKind.Subtitle to subtitles.distinctBy { it.url },
    )

    fun external(kind: PlaybackTrackKind, choiceId: Int): ExternalPlaybackTrack? =
        if (choiceId <= -2) external.getValue(kind).getOrNull(-(choiceId.toLong() + 2).toInt()) else null

    fun loadedId(kind: PlaybackTrackKind, url: String, native: List<NativePlaybackTrack>): Int? =
        native.firstOrNull { it.type == kind.nativeType && it.externalFilename == url }?.id

    fun choices(kind: PlaybackTrackKind, native: List<NativePlaybackTrack>): List<PlaybackTrackChoice> {
        val loaded = native.filter { it.type == kind.nativeType }.distinctBy { it.id }
        val declarations = external.getValue(kind)
        return loaded.filter { track -> declarations.none { it.url == track.externalFilename } }.map { track ->
            PlaybackTrackChoice(track.id, track.title, track.lang)
        } +
            declarations.mapIndexed { index, track ->
                val nativeTrack = loaded.firstOrNull { it.externalFilename == track.url }
                PlaybackTrackChoice(
                    nativeTrack?.id ?: (-2 - index),
                    nativeTrack?.title?.ifBlank { track.title } ?: track.title,
                    nativeTrack?.lang,
                )
            }
    }
}

/** Fast keyframe seeks can leave an independent audio demuxer ahead of the video. */
internal object PlaybackSeekPolicy {
    fun flags(absolute: Boolean, precise: Boolean, externalAudio: Boolean): String {
        val mode = if (absolute) "absolute" else "relative"
        return when {
            precise || externalAudio -> "$mode+exact"
            absolute -> "$mode+keyframes"
            else -> mode
        }
    }
}

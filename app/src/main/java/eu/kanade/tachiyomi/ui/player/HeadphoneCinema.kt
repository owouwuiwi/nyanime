package eu.kanade.tachiyomi.ui.player

/** The order must match both FFmpeg's input layout and the HRIR pairs in the asset. */
enum class CinemaLayout(val nativeName: String, val channels: String, val asset: String, val count: Int) {
    Surround51("5.1", "FL|FR|FC|LFE|BL|BR", "surround51.wav", 6),
    Surround51Side("5.1(side)", "FL|FR|FC|LFE|SL|SR", "surround51side.wav", 6),
    Surround71("7.1", "FL|FR|FC|LFE|BL|BR|SL|SR", "surround71.wav", 8),
    ;

    companion object {
        fun fromNative(value: String?): CinemaLayout? = when (value) {
            "5.1", "fl-fr-fc-lfe-bl-br" -> Surround51
            "5.1(side)", "fl-fr-fc-lfe-sl-sr" -> Surround51Side
            "7.1", "fl-fr-fc-lfe-bl-br-sl-sr" -> Surround71
            else -> null
        }
    }
}

enum class CinemaStatus {
    Original,
    Preparing,
    Active,
    ConnectHeadphones,
    UnsupportedTrack,
    ChannelOverride,
    CustomFilters,
    Unavailable,
}

data class HeadphoneCinemaState(
    val enabled: Boolean = false,
    val status: CinemaStatus = CinemaStatus.Original,
    val layout: CinemaLayout? = null,
)

/**
 * This MPV wrapper exposes af as an option string, not indexed node properties.
 * MPV serializes values containing delimiters as %UTF8-byte-length%value.
 */
internal fun parseAudioFilters(value: String?): List<String>? {
    if (value.isNullOrEmpty()) return emptyList()
    val bytes = value.toByteArray(Charsets.UTF_8)
    val entries = mutableListOf<String>()
    var start = 0
    var index = 0
    while (index < bytes.size) {
        when (bytes[index].toInt().toChar()) {
            '%' -> {
                val end = (index + 1 until bytes.size).firstOrNull { bytes[it] == '%'.code.toByte() } ?: return null
                val size = String(bytes, index + 1, end - index - 1, Charsets.UTF_8).toIntOrNull() ?: return null
                if (size < 0 || size > bytes.size - end - 1) return null
                index = end + 1 + size
            }
            ',' -> {
                entries += String(bytes, start, index - start, Charsets.UTF_8)
                start = ++index
            }
            else -> index++
        }
    }
    entries += String(bytes, start, bytes.size - start, Charsets.UTF_8)
    return entries.takeIf { it.none(String::isBlank) }
}

/**
 * Own exactly one labelled filter. Never clear the filter chain, change volume, seek, or pause.
 * All calls are serialized on the player thread; close must precede native destruction.
 */
internal class CinemaFilter(
    private val command: (Array<String>) -> Unit,
    private val isPresent: () -> Boolean,
) {
    private var applied: String? = null
    private var closed = false

    fun apply(layout: CinemaLayout?, directory: String): Boolean {
        if (closed) return false
        val target = layout?.let { graph(it, directory) }
        if (target == applied) {
            if (target != null) return isPresent()
            remove()
            return true
        }
        remove()
        if (target == null) return true
        return try {
            command(arrayOf("af", "add", "@$LABEL:lavfi=[$target]"))
            check(isPresent())
            applied = target
            true
        } catch (_: Exception) {
            // Roll back even when registration cannot be verified. Never leave an untracked effect.
            runCatching { command(arrayOf("af", "remove", "@$LABEL")) }
            applied = null
            false
        }
    }

    fun remove() {
        if (closed) return
        if (applied != null || isPresent()) command(arrayOf("af", "remove", "@$LABEL"))
        applied = null
    }

    fun close() {
        // Destruction of the player frees its filters. Do not rebuild the audio pipeline on exit.
        closed = true
        applied = null
    }

    companion object {
        const val LABEL = "nyanime_headphone_cinema"

        fun graph(layout: CinemaLayout, directory: String): String {
            // This is an app-private, generated path, never an extension-provided URL.
            require(directory.matches(Regex("[/a-zA-Z0-9._-]+")))
            val gain = 3 * layout.count - 9
            // A decoder can reconfigure before Android receives AUDIO_RECONFIG. Keep the
            // current graph valid for that short interval, then select the new measured layout.
            return "amovie='$directory/${layout.asset}'[hrir];" +
                "[in]aresample=48000,aformat=channel_layouts='${layout.nativeName}'[resampled];" +
                "[resampled][hrir]headphone=map=${layout.channels}:hrir=multich:" +
                "type=freq:size=1024:gain=$gain," +
                "alimiter=limit=0.95:level=false:latency=true[out]"
        }
    }
}

package eu.kanade.tachiyomi.ui.player

/** Native properties can return null successfully while a file opens, seeks or closes. */
internal data class PlayerWatchTiming private constructor(
    val duration: Double = 0.0,
    val position: Double = 0.0,
    val ready: Boolean = false,
) {
    companion object {
        fun read(available: Boolean, readDouble: (String) -> Double?): PlayerWatchTiming {
            if (!available) return PlayerWatchTiming()

            fun seconds(property: String): Double? = runCatching { readDouble(property) }.getOrNull()
                ?.takeIf { it.isFinite() && it in 0.0..86_400.0 }

            val duration = seconds("duration")
            val position = seconds("time-pos")
            return PlayerWatchTiming(
                duration = duration ?: 0.0,
                position = position ?: 0.0,
                ready = duration != null && duration > 0.0 && position != null,
            )
        }
    }
}

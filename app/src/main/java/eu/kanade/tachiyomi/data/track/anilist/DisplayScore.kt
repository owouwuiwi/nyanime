package eu.kanade.tachiyomi.data.track.anilist

// Display overrides must never change the remote account's score encoding.
internal fun displayNumericScore(score: Double, format: String): String = when (format) {
    "POINT_10" -> (score.toInt() / 10).toString()
    "POINT_100" -> score.toInt().toString()
    "POINT_10_DECIMAL" -> (score / 10).toString()
    else -> error("Unsupported numeric score format")
}

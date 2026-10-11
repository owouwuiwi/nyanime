package eu.kanade.tachiyomi.data.tv

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Only the contiguous, native-confirmed cached range containing playback can be sought. */
internal object TvSeekWindow {
    fun read(raw: String?, position: Double): ClosedFloatingPointRange<Double>? {
        if (raw == null || raw.length > 65_536 || !position.isFinite() || position < 0) return null
        val ranges = runCatching {
            (Json.parseToJsonElement(raw) as? JsonObject)?.get("seekable-ranges") as? JsonArray
        }.getOrNull() ?: return null
        if (ranges.size > 32) return null
        val ordered = ranges.mapNotNull { value ->
            val entry = value as? JsonObject ?: return@mapNotNull null
            val start = (entry["start"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val end = (entry["end"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            (start..end).takeIf { start.isFinite() && end.isFinite() && start >= 0 && end > start }
        }.sortedBy { it.start }
        var joined: ClosedFloatingPointRange<Double>? = null
        for (range in ordered) {
            val current = joined
            if (current == null) {
                joined = range
            } else if (range.start <= current.endInclusive) {
                joined = current.start..maxOf(current.endInclusive, range.endInclusive)
            } else {
                if (position in current) return current
                joined = range
            }
        }
        return joined?.takeIf { position in it }
    }
}

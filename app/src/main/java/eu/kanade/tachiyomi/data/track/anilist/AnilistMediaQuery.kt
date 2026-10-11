package eu.kanade.tachiyomi.data.track.anilist

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object AnilistMediaQuery {
    fun variables(title: String, id: Long?) = buildJsonObject {
        if (id == null) {
            put("query", title)
        } else {
            require(id in 1..Int.MAX_VALUE.toLong()) { "Invalid catalogue ID" }
            // A verified ID must not also be restricted by a translated or abbreviated title.
            put("id", id)
        }
    }
}

package tachiyomi.domain.items.episode.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** Optional source-owned presentation. Never infer a group from an URL, title or scanlator. */
@Serializable
data class EpisodeGroup(val id: String, val title: String, val order: Int) {
    fun attachTo(memo: JsonObject): JsonObject {
        require(valid())
        return JsonObject(memo + (KEY to json.encodeToJsonElement(this)))
    }

    private fun valid() = id.isNotBlank() &&
        id.length <= 1024 &&
        title.isNotBlank() &&
        title.length <= 200 &&
        order in 0..100_000 &&
        id.none(Char::isISOControl) &&
        title.none(Char::isISOControl)

    companion object {
        const val KEY = "nyanime.episode-group.v1"
        private val json = Json { ignoreUnknownKeys = true }

        fun from(memo: JsonObject): EpisodeGroup? = memo[KEY]?.let { value ->
            if (value == JsonNull || value.toString().length > 4096) return null
            runCatching { json.decodeFromJsonElement<EpisodeGroup>(value).takeIf { it.valid() } }.getOrNull()
        }

        /** An incomplete response cannot erase known grouping. Explicit null clears it. */
        fun mergeMemo(saved: JsonObject, incoming: JsonObject): JsonObject =
            if (KEY !in incoming && from(saved) != null) {
                JsonObject(incoming + (KEY to saved.getValue(KEY)))
            } else {
                incoming
            }
    }
}

val Episode.group: EpisodeGroup? get() = EpisodeGroup.from(memo)

/** Repeated episode numbers are only duplicates inside the same declared group. */
data class EpisodeProgressKey(val groupId: String?, val number: Double)

val Episode.progressKey get() = EpisodeProgressKey(group?.id, episodeNumber)

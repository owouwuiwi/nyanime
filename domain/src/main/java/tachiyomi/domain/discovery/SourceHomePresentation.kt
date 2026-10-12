package tachiyomi.domain.discovery

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import tachiyomi.domain.entries.anime.model.Anime

/** Public, source-owned card data; never a replacement for library identity or episode progress. */
@Serializable
data class SourceHomePresentation(
    val id: String? = null,
    val badges: List<String> = emptyList(),
    val details: List<String> = emptyList(),
    val sectionTitle: String? = null,
    val logoUrl: String? = null,
    val logoName: String? = null,
    val logoBackground: String? = null,
    /** Shared catalogue identifiers supplied by an extension, never by the host. */
    val catalogIds: Map<String, Long> = emptyMap(),
    val aliases: List<String> = emptyList(),
    val releaseYear: Int? = null,
    val rank: Int? = null,
    val episodeTarget: String? = null,
    /** Concrete local choices added only while combining installed providers. */
    val choices: List<SourceHomeChoice> = emptyList(),
) {
    fun bounded() = copy(
        id = id?.takeIf { valid(it, 512) },
        badges = badges.filter { valid(it, 80) }.distinct().take(8),
        details = details.filter { valid(it, 300) }.distinct().take(8),
        sectionTitle = sectionTitle?.takeIf { valid(it, 100) },
        logoName = logoName?.takeIf { valid(it, 80) },
        logoBackground = logoBackground?.takeIf { it in listOf("light", "dark") },
        catalogIds = catalogIds.filter { (key, value) -> valid(key, 40) && value > 0 }.toList().take(8).toMap(),
        aliases = aliases.filter { valid(it, 200) }.distinct().take(8),
        releaseYear = releaseYear?.takeIf { it in 1900..2100 },
        rank = rank?.takeIf { it in 1..100_000 },
        episodeTarget = episodeTarget?.takeIf { valid(it, 300) },
        choices = choices.filter { it.animeId > 0 && it.sourceId > 0 && valid(it.title, 200) }
            .distinctBy { it.animeId }.take(8),
        logoUrl = logoUrl?.takeIf { value ->
            valid(value, 2048) &&
                runCatching {
                    val uri = java.net.URI(value)
                    uri.scheme in listOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null
                }.getOrDefault(false)
        },
    )

    fun attachTo(memo: JsonObject) = JsonObject(without(memo) + (KEY to json.encodeToJsonElement(bounded())))

    companion object {
        const val KEY = "aniyomi.home.v1"
        private val json = Json { ignoreUnknownKeys = true }
        private fun valid(value: String, max: Int) =
            value.isNotBlank() && value.length <= max && value.none(Char::isISOControl)

        fun without(memo: JsonObject) = JsonObject(memo - KEY)

        fun from(memo: JsonObject): SourceHomePresentation? = memo[KEY]?.let { value ->
            if (value.toString().length > 8192) return null
            runCatching { json.decodeFromJsonElement<SourceHomePresentation>(value).bounded() }.getOrNull()
        }
    }
}

@Serializable
data class SourceHomeChoice(val animeId: Long, val sourceId: Long, val title: String, val episodeTarget: String? = null)

val Anime.homePresentation get() = SourceHomePresentation.from(memo)

/** Length prefixes avoid collisions; Compose keys and pagination share the same concrete card identity. */
val Anime.homeItemKey: String get() {
    val entry = homePresentation?.id.orEmpty()
    return "$source:${url.length}:$url:${entry.length}:$entry"
}

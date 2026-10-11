package eu.kanade.tachiyomi.data.discovery

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Declarative v1 contract: labels refer exclusively to the extension's public select filters. */
@Serializable
data class ExtensionHomeManifest(
    val id: String,
    val title: String,
    val source: Source,
    val defaults: Map<String, String> = emptyMap(),
    val sections: List<Section>,
    val search: Section? = null,
    val categories: Categories? = null,
    val primary: Boolean = false,
    val browseFilters: List<String> = emptyList(),
    val random: Section? = null,
    val randomEpisode: Section? = null,
) {
    @Serializable
    data class Source(val name: String, val lang: String)

    @Serializable
    data class Section(
        val id: String,
        val title: String,
        val filters: Map<String, String> = emptyMap(),
        val layout: String = "posters",
        val group: Group? = null,
        val dateFilter: String? = null,
        val moreFilters: Map<String, String>? = null,
    )

    @Serializable
    data class Group(val id: String, val title: String, val tab: String)

    @Serializable
    data class Categories(val filter: String, val exclude: List<String> = emptyList())

    private fun valid(): Boolean = id.matches(ID) &&
        text(title) &&
        text(source.name) &&
        text(source.lang) &&
        selections(defaults) &&
        browseFilters.size <= 24 &&
        browseFilters.distinct().size == browseFilters.size &&
        browseFilters.all(::text) &&
        sections.size in 1..16 &&
        sections.map { it.id }.distinct().size == sections.size &&
        sections.all { it.id.matches(ID) && it.id != "search" && text(it.title) && selections(it.filters) } &&
        sections.all { it.moreFilters == null || selections(it.moreFilters) } &&
        sections.all { it.dateFilter == null || (text(it.dateFilter) && it.dateFilter !in (defaults + it.filters)) } &&
        sections.mapNotNull { it.group }.all { it.id.matches(ID) && text(it.title) && text(it.tab) } &&
        sections.mapNotNull { it.group }.groupBy { it.id }.values.all { variants ->
            variants.map { it.title }.distinct().size == 1 && variants.map { it.tab }.distinct().size == variants.size
        } &&
        (search == null || (search.id == "search" && text(search.title) && selections(search.filters))) &&
        (random == null || (random.id == "random" && text(random.title) && selections(random.filters))) &&
        (
            randomEpisode == null ||
                (randomEpisode.id == "random-episode" && text(randomEpisode.title) && selections(randomEpisode.filters))
            ) &&
        (
            categories == null ||
                (text(categories.filter) && categories.exclude.size <= 100 && categories.exclude.all(::text))
            )

    companion object {
        const val ASSET_PATH = "assets/aniyomi/home-v1.json"
        const val MAX_BYTES = 65_536
        private val ID = Regex("[a-z][a-z0-9-]{0,47}")
        private val json = Json { ignoreUnknownKeys = true }
        private fun text(value: String) = value.isNotBlank() && value.length <= 100 && value.none(Char::isISOControl)
        private fun selections(values: Map<String, String>) =
            values.size <= 16 && values.all { text(it.key) && text(it.value) }

        fun parse(content: String): List<ExtensionHomeManifest> {
            if (content.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return emptyList()
            return runCatching {
                val root = json.parseToJsonElement(content).jsonObject
                if (root["version"]?.jsonPrimitive?.intOrNull != 1) return emptyList()
                val homes = root.getValue("homes").jsonArray
                if (homes.size > 8) return emptyList()
                // One invalid declaration cannot suppress the other homes or the catalogue.
                homes.mapNotNull { item ->
                    runCatching {
                        json.decodeFromJsonElement<ExtensionHomeManifest>(item)
                    }.getOrNull()?.takeIf { it.valid() }
                }.groupBy { it.id }.values.filter { it.size == 1 }.flatten()
            }.getOrDefault(emptyList())
        }
    }
}

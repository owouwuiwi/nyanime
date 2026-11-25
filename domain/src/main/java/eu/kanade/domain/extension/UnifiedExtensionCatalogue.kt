package eu.kanade.domain.extension

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Media are declared by a catalogue, never inferred from package or source names. */
object ExtensionCatalogueMedium {
    const val ANIME = "anime"
    const val MANGA = "manga"
    const val NEWS = "news"
    const val ADDON = "addon"

    fun accepts(declared: String?, requested: String): Boolean = declared == null || declared == requested
}

@Serializable
data class UnifiedExtensionCatalogue(
    val name: String,
    val badgeLabel: String,
    val signingKey: String,
    val contact: Contact,
    val media: Set<String>,
    val extensionList: ExtensionList,
    val replaces: Set<String> = emptySet(),
    private val packageList: JsonElement? = null,
    val catalogues: List<LinkedCatalogue> = emptyList(),
) {
    init {
        require(catalogues.size <= MAX_LINKED_CATALOGUES)
        require(catalogues.distinctBy { it.canonicalUrl }.size == catalogues.size) { "Duplicate linked catalogue URL" }
        val manga = catalogues.filter { it.type == LinkedCatalogue.Type.MANGA }
        require(manga.distinctBy { it.signingKey }.size == manga.size) { "Duplicate linked manga catalogue signing key" }
    }

    @Serializable data class Contact(val website: String, val discord: String? = null)

    @Serializable data class ExtensionList(val extensions: List<Entry>)

    @Serializable data class Entry(val packageName: String, val medium: String)

    @Serializable
    data class LinkedCatalogue(val url: String, val type: Type, val signingKey: String) {
        init {
            require(
                (url.endsWith(".json") && validUrl(url)) ||
                    (url.endsWith(".pb") && ExtensionCatalogueLink.isLegacyRepositoryUrl(url)),
            ) { "A linked catalogue needs a direct HTTPS index" }
            require(signingKey.matches(SIGNING_KEY)) { "Invalid linked catalogue signing key" }
        }

        internal val canonicalUrl: String get() = requireNotNull(canonicalIndexUrl(url))

        fun isAt(url: String): Boolean = canonicalUrl == canonicalIndexUrl(url)

        @Serializable
        enum class Type {
            @SerialName("anime")
            ANIME,

            @SerialName("manga")
            MANGA,
        }
    }

    fun count(medium: String): Int = extensionList.extensions.count { it.medium == medium }

    val bridgeCount: Int
        get() {
            val packages = (packageList as? JsonObject)?.get("extensions") as? JsonArray
            return packages.orEmpty().count { entry ->
                val descriptor = entry as? JsonObject
                val format = (descriptor?.get("format") as? JsonPrimitive)?.content
                val manifest = descriptor?.get("manifest") as? JsonObject
                val medium = (manifest?.get("medium") as? JsonPrimitive)?.content
                format == "apk" && (medium == "layer" || medium == "manga-layer")
            }
        }

    companion object {
        const val MAX_BYTES = 8L * 1024 * 1024
        private const val MAX_LINKED_CATALOGUES = 16
        private val SIGNING_KEY = Regex("[a-f0-9]{64}")
        private val json = Json { ignoreUnknownKeys = true }

        internal fun canonicalIndexUrl(value: String): String? = value.toHttpUrlOrNull()?.toString()

        fun validUrl(value: String): Boolean = (ExtensionCatalogueLink.parse(value) as? ExtensionCatalogueLink.Index)
            ?.url == value

        fun parse(value: String): UnifiedExtensionCatalogue {
            require(value.toByteArray().size <= MAX_BYTES)
            return json.decodeFromString<UnifiedExtensionCatalogue>(value).also { catalogue ->
                require(catalogue.name.isNotBlank() && catalogue.name.length <= 100)
                require(catalogue.badgeLabel.isNotBlank() && catalogue.badgeLabel.length <= 30)
                require(catalogue.signingKey.matches(SIGNING_KEY))
                require(
                    catalogue.media.isNotEmpty() &&
                        catalogue.media.all { it in setOf("anime", "manga", "news", "addon", "tv", "guide") },
                )
                require(
                    catalogue.extensionList.extensions.all {
                        it.medium in catalogue.media &&
                            it.packageName.isNotBlank()
                    },
                )
                require(
                    catalogue.extensionList.extensions.map { it.packageName }.distinct().size ==
                        catalogue.extensionList.extensions.size,
                )
                require(catalogue.replaces.all(::validUrl))
            }
        }
    }
}

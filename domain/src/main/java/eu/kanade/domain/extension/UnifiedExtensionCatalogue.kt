package eu.kanade.domain.extension

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI

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
    val authority: Authority? = null,
) {
    /** A recommendation is bound to this catalogue's own pinned APK signer, never a global permission. */
    @Serializable data class Authority(val version: Int = 1, val recommendReplacements: Boolean = false)

    @Serializable data class Contact(val website: String, val discord: String? = null)

    @Serializable data class ExtensionList(val extensions: List<Entry>)

    @Serializable data class Entry(val packageName: String, val medium: String)

    fun count(medium: String): Int = extensionList.extensions.count { it.medium == medium }

    companion object {
        const val MAX_BYTES = 8L * 1024 * 1024
        private val json = Json { ignoreUnknownKeys = true }

        fun validUrl(value: String): Boolean = runCatching {
            val url = URI(value)
            url.scheme == "https" &&
                !url.host.isNullOrBlank() &&
                url.rawUserInfo == null &&
                url.rawQuery == null &&
                url.rawFragment == null &&
                url.path.endsWith(".json")
        }.getOrDefault(false)

        fun parse(value: String): UnifiedExtensionCatalogue {
            require(value.toByteArray().size <= MAX_BYTES)
            return json.decodeFromString<UnifiedExtensionCatalogue>(value).also { catalogue ->
                require(catalogue.name.isNotBlank() && catalogue.name.length <= 100)
                require(catalogue.badgeLabel.isNotBlank() && catalogue.badgeLabel.length <= 30)
                require(catalogue.signingKey.matches(Regex("[a-f0-9]{64}")))
                require(
                    catalogue.media.isNotEmpty() &&
                        catalogue.media.all { it in setOf("anime", "manga", "news", "addon") },
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
                require(catalogue.authority == null || catalogue.authority.version == 1)
            }
        }
    }
}

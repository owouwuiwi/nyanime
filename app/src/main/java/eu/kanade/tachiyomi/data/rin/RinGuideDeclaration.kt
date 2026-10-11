package eu.kanade.tachiyomi.data.rin

import kotlinx.serialization.Serializable

/** Capability declaration of a non-source guide extension. It never registers playable sources. */
@Serializable
data class RinGuideDeclaration(
    val api: Int,
    val capabilities: List<String>,
    val descriptions: Map<String, String>,
) {
    fun valid() = api == 1 &&
        capabilities.size in 1..16 &&
        capabilities.distinct().size == capabilities.size &&
        capabilities.all { it.matches(Regex("[a-z][a-z0-9_.-]{1,64}")) } &&
        descriptions.size in 1..50 &&
        "en" in descriptions &&
        descriptions.all { (locale, text) ->
            locale.matches(Regex("[A-Za-z0-9-]{1,35}")) && text.isNotBlank() && text.length <= 4096
        }
}

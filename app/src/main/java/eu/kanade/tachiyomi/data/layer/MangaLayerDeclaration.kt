package eu.kanade.tachiyomi.data.layer

import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
data class MangaLayerDeclaration(
    val api: Int,
    val targets: List<MangaLayerTarget>,
    val presentation: Map<String, SourceLayerPresentation> = emptyMap(),
) {
    fun valid(): Boolean = api == 1 && targets.size in 1..32 &&
        targets.all(MangaLayerTarget::valid) &&
        targets.distinctBy { Triple(it.medium, it.packageName, it.sourceId) }.size == targets.size &&
        presentation.size <= 8 && presentation.all { (language, value) ->
            LANGUAGE_TAG.matches(language) && value.valid()
        }

    fun presentationFor(locale: Locale): SourceLayerPresentation? =
        presentation[locale.toLanguageTag()] ?: presentation[locale.language] ?: presentation["en"]
            ?: presentation.values.firstOrNull()

    companion object {
        private val LANGUAGE_TAG = Regex("[a-z]{2,3}(?:-[a-zA-Z0-9]{2,8}){0,3}")
    }
}

@Serializable
data class SourceLayerPresentation(
    val summary: String,
    val features: List<SourceLayerFeature>,
) {
    fun valid(): Boolean = summary.isNotBlank() && summary.length <= 350 &&
        features.size in 1..8 && features.all { it.valid() }
}

@Serializable
data class SourceLayerFeature(val title: String, val description: String) {
    fun valid(): Boolean = title.isNotBlank() && title.length <= 70 &&
        description.isNotBlank() && description.length <= 420
}

@Serializable
data class MangaLayerTarget(
    val packageName: String,
    val sourceId: Long,
    val signer: String,
    val minVersionCode: Long,
    val maxVersionCode: Long? = null,
    val capabilities: Set<String>,
    val requires: Map<String, Set<String>> = emptyMap(),
    val medium: String = "manga",
) {
    fun valid(): Boolean = packageName.matches(PACKAGE) && (sourceId == 0L) == (medium == "news") &&
        signer.matches(SIGNER) && minVersionCode > 0 &&
        (maxVersionCode == null || maxVersionCode >= minVersionCode) &&
        capabilities.isNotEmpty() && capabilitiesFor(medium).containsAll(capabilities) &&
        requires.all { (capability, dependencies) ->
            capability in capabilities && dependencies.isNotEmpty() &&
                capabilitiesFor(medium).containsAll(dependencies) && capability !in dependencies
        }

    fun supports(versionCode: Long): Boolean =
        versionCode >= minVersionCode && (maxVersionCode == null || versionCode <= maxVersionCode)

    fun matches(versionCode: Long, currentSigners: Set<String>, signerHistory: Set<String>): Boolean =
        supports(versionCode) && currentSigners.size == 1 && signer in (currentSigners + signerHistory)

    companion object {
        fun capabilitiesFor(medium: String): Set<String> = when (medium) {
            "manga" -> setOf("home", "catalog", "update", "identity", "reader")
            "anime" -> setOf("home", "catalog", "update", "identity", "player")
            "news" -> setOf("feed", "article", "search")
            "tv" -> setOf("catalog", "guide", "player")
            else -> emptySet()
        }
        private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        private val SIGNER = Regex("[a-f0-9]{64}")
    }
}

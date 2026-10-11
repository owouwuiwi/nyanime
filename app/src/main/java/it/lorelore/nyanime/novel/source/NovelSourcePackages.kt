package it.lorelore.nyanime.novel.source

import eu.kanade.tachiyomi.data.rin.RinManifest
import it.lorelore.nyanime.source.novel.NovelSource

/** A versioned optional family, independent of the retired publication runtime. */
internal object NovelSourcePackages {
    const val FEATURE = "nyanime.novelextension"
    const val API = "nyanime.novel.api"
    const val FACTORY = "nyanime.novel.factory"
    const val MIN_HOST_VERSION = 289L

    fun compatible(manifest: RinManifest): Boolean =
        manifest.medium == "novel" &&
            manifest.schema == 2 &&
            manifest.extensionLib == 1.0 &&
            manifest.minHostVersionCode?.let { it >= MIN_HOST_VERSION } == true &&
            manifest.validSourceIdentities() &&
            manifest.entryPoints.size in 1..32 &&
            manifest.optionalFactories.isEmpty()

    fun verify(manifest: RinManifest, sources: List<NovelSource>): List<NovelSource> {
        require(compatible(manifest)) { "Unsupported novel source contract" }
        require(sources.size in 1..128 && sources.map { it.id }.distinct().size == sources.size)
        val declared = manifest.sources.associateBy { it.id }
        require(sources.map { it.id }.toSet() == declared.keys) {
            "Novel source identities changed"
        }
        sources.forEach { source ->
            val identity = requireNotNull(declared[source.id])
            require(
                source.id != 0L && source.name == identity.name && source.lang == identity.language,
            )
            require(!source.isNsfw || manifest.nsfw) { "Missing content declaration" }
        }
        return sources
    }
}

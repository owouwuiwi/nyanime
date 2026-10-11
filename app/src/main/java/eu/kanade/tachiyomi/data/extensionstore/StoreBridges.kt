package eu.kanade.tachiyomi.data.extensionstore

import eu.kanade.tachiyomi.data.layer.MangaLayerChoice
import eu.kanade.tachiyomi.data.layer.MangaLayerStatus
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import java.util.Locale

internal enum class StoreBridgeState {
    NEEDS_SOURCE, AVAILABLE, TRUST, DISABLED, ACTIVE, PARTIALLY_ACTIVE, INCOMPATIBLE, DEPENDENCY_MISSING, FAILED, INSTALLED,
}

internal data class StoreBridge(
    val entry: StoreEntry,
    val compatible: Boolean,
    val state: StoreBridgeState,
    val capabilities: Set<Pair<Long, String>>,
) {
    val isActive get() = state in setOf(StoreBridgeState.ACTIVE, StoreBridgeState.PARTIALLY_ACTIVE)
}

internal object StoreBridges {
    fun forInstallation(bridges: List<StoreBridge>): List<StoreEntry> {
        val available = bridges.filter { it.compatible && !it.entry.installed && it.entry.rinCandidate != null }
            .groupBy { it.entry.packageName }
            .values.mapNotNull { variants ->
                variants.distinctBy {
                    val candidate = requireNotNull(it.entry.rinCandidate)
                    Triple(candidate.manifest, candidate.signer, candidate.sha256)
                }.singleOrNull()
            }
        val counts = available.flatMap { it.capabilities }.groupingBy { it }.eachCount()
        return available.filter { bridge -> bridge.capabilities.all { counts[it] == 1 } }.map { it.entry }
    }

    fun bySource(
        entries: List<StoreEntry>,
        choices: List<MangaLayerChoice>,
        adult: Boolean,
    ): Map<String, List<StoreBridge>> {
        val candidates = entries.asSequence()
            .filter { it.privateOnly && (it.installed || adult || !it.nsfw) }
            .flatMap { entry ->
                val targets = (entry.rinRecord?.manifest ?: entry.rinCandidate?.manifest)?.mangaLayer?.targets.orEmpty()
                targets.map { it.packageName }.distinct().map { it to entry }
            }
            .groupBy({ it.first }, { it.second })
        val statuses = choices.groupBy { it.basePackage to it.packageName }
        return buildMap {
            for (base in entries) {
                val layers = candidates[base.packageName] ?: continue
                val related = layers.mapNotNull { relation(base, it, statuses[base.packageName to it.packageName].orEmpty()) }
                if (related.isNotEmpty()) {
                    put(base.id, related.sortedWith(
                        compareByDescending<StoreBridge> { it.entry.installed }.thenBy { it.entry.name.lowercase(Locale.ROOT) },
                    ))
                }
            }
        }
    }

    private fun relation(base: StoreEntry, bridge: StoreEntry, choices: List<MangaLayerChoice>): StoreBridge? {
        val medium = when (base.medium) {
            StoreMedium.ANIME -> "anime"
            StoreMedium.MANGA -> "manga"
            StoreMedium.NEWS -> "news"
            StoreMedium.TV -> "tv"
            else -> return null
        }
        val version = if (base.installed) base.installedVersion else base.candidateVersion
        val metadata = base.installedMetadata
        val publisher = if (base.installed) metadata?.signers.orEmpty()
            else setOfNotNull(base.candidateSigner?.lowercase(Locale.ROOT))
        val history = if (base.installed) metadata?.signerHistory.orEmpty() else emptySet()
        val manifest = if (base.installed) base.rinRecord?.manifest else base.rinCandidate?.manifest
        val sources = buildSet {
            manifest?.sources?.forEach { add(it.id) }
            if (base.installed) {
                (base.manga as? MangaExtension.Installed)?.sources?.forEach { add(it.id) }
                (base.anime as? AnimeExtension.Installed)?.sources?.forEach { add(it.id) }
            } else {
                base.mangaCandidate?.sources?.forEach { add(it.id) }
                base.animeCandidate?.sources?.forEach { add(it.id) }
            }
            if (medium == "news") add(0L)
        }
        val declaration = (bridge.rinRecord?.manifest ?: bridge.rinCandidate?.manifest)?.mangaLayer ?: return null
        val matching = declaration.targets.filter {
            it.medium == medium && it.packageName == base.packageName && it.sourceId in sources
        }
        if (matching.isEmpty()) return null
        val supported = if (version == null) emptyList() else matching.filter { it.matches(version, publisher, history) }
        val compatible = supported.isNotEmpty()
        if (!compatible && (!base.installed || !bridge.installed)) return null

        val active = choices.count { it.status == MangaLayerStatus.ACTIVE }
        val state = when {
            !base.installed -> StoreBridgeState.NEEDS_SOURCE
            !compatible -> StoreBridgeState.INCOMPATIBLE
            !bridge.installed -> StoreBridgeState.AVAILABLE
            bridge.action == StoreAction.TRUST -> StoreBridgeState.TRUST
            !bridge.enabled || !base.enabled -> StoreBridgeState.DISABLED
            active > 0 && active < choices.size -> StoreBridgeState.PARTIALLY_ACTIVE
            active > 0 -> StoreBridgeState.ACTIVE
            choices.any { it.status == MangaLayerStatus.FAILED } -> StoreBridgeState.FAILED
            choices.any { it.status == MangaLayerStatus.DEPENDENCY_MISSING } -> StoreBridgeState.DEPENDENCY_MISSING
            choices.isNotEmpty() && choices.all { it.status == MangaLayerStatus.INCOMPATIBLE } -> StoreBridgeState.INCOMPATIBLE
            else -> StoreBridgeState.INSTALLED
        }
        val capabilities = supported.flatMap { target -> target.capabilities.map { target.sourceId to it } }.toSet()
        return StoreBridge(bridge, compatible, state, capabilities)
    }
}

package eu.kanade.tachiyomi.data.extensionstore

import eu.kanade.tachiyomi.data.rin.RinEntry
import eu.kanade.tachiyomi.data.rin.RinRecord
import eu.kanade.tachiyomi.data.rin.RinUpdatePolicy
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension

internal object RinStoreCatalogue {
    fun entries(
        candidates: List<RinEntry>,
        installed: List<RinRecord>,
        anime: List<AnimeExtension.Installed>,
        manga: List<MangaExtension.Installed>,
    ): List<StoreEntry> {
        val a = anime.associateBy { it.pkgName }
        val m = manga.associateBy { it.pkgName }
        val records = installed.associateBy { it.manifest.packageName }
        val available = candidates.groupBy { it.manifest.packageName }
        val owned = installed.map { record ->
            val candidate = available[record.manifest.packageName].orEmpty().filter {
                it.repository == record.repository && it.signer == record.signer
            }.maxByOrNull { it.manifest.versionCode }
            entry(candidate, record, a[record.manifest.packageName], m[record.manifest.packageName])
        }
        val discover = candidates.filterNot { it.manifest.packageName in records }
            .groupBy { Triple(it.manifest.packageName, it.repository, it.signer) }
            .values.map { versions -> entry(versions.maxBy { it.manifest.versionCode }, null, null, null) }
        return owned + discover
    }

    private fun entry(
        candidate: RinEntry?,
        record: RinRecord?,
        anime: AnimeExtension.Installed?,
        manga: MangaExtension.Installed?,
    ): StoreEntry {
        val manifest = requireNotNull(record?.manifest ?: candidate?.manifest)
        val repository = record?.repository ?: requireNotNull(candidate).repository
        return StoreEntry(
            id = "rin:$repository:${manifest.packageName}",
            packageName = manifest.packageName,
            name = manifest.name,
            medium = when (manifest.medium) {
                "tv" -> StoreMedium.TV
                "guide" -> StoreMedium.GUIDE
                "addon" -> StoreMedium.ADDON
                "anime" -> StoreMedium.ANIME
                "manga" -> StoreMedium.MANGA
                else -> error("Unknown RIN medium")
            },
            ready = eu.kanade.tachiyomi.data.rin.RinRepository.compatible(manifest),
            languages = manifest.sources.map { it.language }.toSet(),
            version = manifest.versionName,
            repository = repository,
            repositoryName = "Nyanime RIN",
            action = if (record ==
                null
            ) {
                StoreAction.INSTALL
            } else if (manifest.medium in setOf("addon", "guide", "tv") &&
                !eu.kanade.tachiyomi.data.rin.RinRepository.compatible(manifest)
            ) {
                StoreAction.MANAGE
            } else {
                StoreAction.OPEN
            },
            installed = record != null,
            nsfw = manifest.nsfw,
            rinCandidate = candidate,
            rinRecord = record,
            anime = anime,
            manga = manga,
            automaticUpdatePending = record != null && candidate != null && RinUpdatePolicy.accepts(candidate, record),
        )
    }
}

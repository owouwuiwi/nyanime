package eu.kanade.tachiyomi.data.extensionstore

import eu.kanade.domain.extension.ExtensionHomeSupport
import eu.kanade.tachiyomi.data.addon.AddonExtension
import eu.kanade.tachiyomi.data.addon.AddonExtensionCatalogue
import eu.kanade.tachiyomi.data.news.NewsExtension
import eu.kanade.tachiyomi.data.news.NewsExtensionCatalogue
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.extension.anime.util.AnimeExtensionLoader
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import eu.kanade.tachiyomi.extension.manga.util.MangaExtensionLoader

enum class StoreMedium { ANIME, MANGA, NEWS, TV, GUIDE, ADDON, NOVEL }
enum class StoreAction { INSTALL, OPEN, UPDATE, TRUST, MANAGE }

/** Presentation data comes exclusively from configured catalogues and inspected packages. */
data class StoreEntry(
    val id: String,
    val packageName: String,
    val name: String,
    val medium: StoreMedium,
    val ready: Boolean,
    val languages: Set<String>,
    val version: String,
    val repository: String?,
    val repositoryName: String?,
    val action: StoreAction,
    val installed: Boolean,
    val nsfw: Boolean = false,
    val anime: AnimeExtension? = null,
    val manga: MangaExtension? = null,
    val addon: AddonExtension? = null,
    val addonCandidate: AddonExtensionCatalogue.Entry? = null,
    val news: NewsExtension? = null,
    val newsCandidate: NewsExtensionCatalogue.Entry? = null,
    val animeCandidate: AnimeExtension.Available? = null,
    val mangaCandidate: MangaExtension.Available? = null,
    val rinCandidate: eu.kanade.tachiyomi.data.rin.RinEntry? = null,
    val automaticUpdatePending: Boolean = false,
    val rinRecord: eu.kanade.tachiyomi.data.rin.RinRecord? = null,
) {
    val addonHostCompatible get() = (
        addonCandidate?.hostVersions ?: addon?.hostVersions
            ?: (rinRecord?.manifest ?: rinCandidate?.manifest)?.takeIf {
                it.medium in setOf("addon", "guide", "tv", "novel")
            }?.hostVersions
        )
        ?.supports(eu.kanade.tachiyomi.BuildConfig.VERSION_CODE.toLong()) != false
    val isRin get() = rinRecord != null || rinCandidate != null
    val enabled get() = rinRecord?.enabled ?: addon?.enabled ?: true
    val canImport get() = when {
        rinRecord != null -> true // Stored records were already verified as component-free.
        addonCandidate != null -> !addonCandidate.requiresAndroid
        addon != null -> false // Legacy Android addon declarations require registered components.
        else -> rinCandidate?.requiresAndroid != true
    }
    val installedVersion get() = rinRecord?.manifest?.versionCode ?: (anime as? AnimeExtension.Installed)?.versionCode
        ?: (manga as? MangaExtension.Installed)?.versionCode ?: news?.versionCode ?: addon?.versionCode
    val installedMetadata get() = (anime as? AnimeExtension.Installed)?.metadata
        ?: (manga as? MangaExtension.Installed)?.metadata ?: news?.metadata ?: addon?.metadata
        ?: rinRecord?.let {
            eu.kanade.domain.extension.ExtensionPackageMetadata(
                signers = it.publisherSigners,
                signerHistory = it.publisherHistory,
            )
        }
    val imported get() = rinRecord?.isShared == false || privatelyInstalled
    val automaticUpdates get() = rinRecord?.automaticUpdates ?: true
    private val searchTerms = buildList {
        add(name)
        add(packageName)
        (rinRecord?.manifest ?: rinCandidate?.manifest)?.sources?.forEach {
            add(it.name)
            add(it.id.toString())
        }
        (anime as? AnimeExtension.Installed)?.sources?.forEach {
            add(it.name)
            add(it.id.toString())
        }
        (manga as? MangaExtension.Installed)?.sources?.forEach {
            add(it.name)
            add(it.id.toString())
        }
        (animeCandidate ?: anime as? AnimeExtension.Available)?.sources?.forEach {
            add(it.name)
            add(it.id.toString())
            add(it.baseUrl)
        }
        (mangaCandidate ?: manga as? MangaExtension.Available)?.sources?.forEach {
            add(it.name)
            add(it.id.toString())
            add(it.baseUrl)
        }
    }

    fun matchesQuery(query: String): Boolean = query.isBlank() ||
        query.split(',').any { part ->
            val term = part.trim()
            term.isNotEmpty() && searchTerms.any { it.contains(term, ignoreCase = true) }
        }

    fun matchesLanguages(enabled: Set<String>): Boolean =
        installed ||
            languages.isEmpty() ||
            languages.any { it in enabled } ||
            (medium == StoreMedium.TV && "all" in languages && enabled.isNotEmpty())

    val candidateUrl get() = rinCandidate?.url ?: animeCandidate?.apkUrl ?: mangaCandidate?.apkUrl
        ?: newsCandidate?.resources?.apkUrl
        ?: addonCandidate?.resources?.apkUrl
    val candidateVersion get() = rinCandidate?.manifest?.versionCode ?: animeCandidate?.versionCode
        ?: mangaCandidate?.versionCode
        ?: newsCandidate?.versionCode ?: addonCandidate?.versionCode
    val candidateSigner get() = rinCandidate?.signer ?: animeCandidate?.let { it.expectedSigner ?: it.store.signingKey }
        ?: mangaCandidate?.expectedSigner ?: newsCandidate?.signer ?: addonCandidate?.signer
    val candidateDistribution get() = animeCandidate?.distributionId ?: mangaCandidate?.distributionId
        ?: newsCandidate?.nyanimeDistributionId ?: addonCandidate?.nyanimeDistributionId
    val candidateHash get() = rinCandidate?.sha256 ?: animeCandidate?.apkSha256 ?: mangaCandidate?.apkSha256
        ?: newsCandidate?.sha256
        ?: addonCandidate?.sha256
    val candidateApi get() = rinCandidate?.manifest?.extensionLib ?: animeCandidate?.libVersion
        ?: mangaCandidate?.libVersion
        ?: newsCandidate?.extensionLib?.toDoubleOrNull() ?: addonCandidate?.extensionLib?.toDoubleOrNull()
    val candidateVersionName get() = rinCandidate?.manifest?.versionName ?: animeCandidate?.versionName
        ?: mangaCandidate?.versionName
        ?: newsCandidate?.versionName ?: addonCandidate?.versionName
    val privatelyInstalled get() = (anime as? AnimeExtension.Installed)?.isShared == false ||
        (manga as? MangaExtension.Installed)?.isShared == false
}

object StoreCatalogue {
    fun supportsHome(version: Int?) = version == 1

    /** Available and installed cards have different keys; an open sheet follows the actual package. */
    fun currentEntry(selected: StoreEntry, entries: List<StoreEntry>): StoreEntry? =
        entries.firstOrNull { it.id == selected.id } ?: entries.firstOrNull {
            it.installed && it.medium == selected.medium && it.packageName == selected.packageName
        }

    fun anime(
        installed: List<AnimeExtension.Installed>,
        untrusted: List<AnimeExtension.Untrusted>,
        available: List<AnimeExtension.Available>,
    ): List<StoreEntry> {
        val candidates = available.groupBy { it.pkgName }
        val result = installed.map { old ->
            val variants = candidates[old.pkgName].orEmpty().groupBy { it.store.indexUrl }
                .values.mapNotNull { it.maxByOrNull(AnimeExtension.Available::versionCode) }
            val updateCandidate = variants.singleOrNull()
                ?: variants.singleOrNull { it.store.indexUrl == old.store?.indexUrl }
            val ready = old.metadata.home in setOf(ExtensionHomeSupport.READY, ExtensionHomeSupport.PARTIAL)
            // Optional Home capabilities do not change the package installation mode.
            val candidate = updateCandidate
            val action = if (old.hasUpdate) StoreAction.UPDATE else StoreAction.OPEN
            StoreEntry(
                "ANIME:${old.pkgName}", old.pkgName, old.name, StoreMedium.ANIME,
                ready ||
                    (
                        action == StoreAction.UPDATE &&
                            supportsHome(candidate?.advertisedHomeVersion)
                        ),
                old.sources.map { it.lang }.toSet(), old.versionName,
                candidate?.store?.indexUrl ?: old.store?.indexUrl, candidate?.store?.name ?: old.store?.name,
                action, true, old.isNsfw, anime = old, animeCandidate = candidate,
            )
        } +
            untrusted.map { old ->
                StoreEntry(
                    "ANIME:${old.pkgName}", old.pkgName, old.name, StoreMedium.ANIME, false,
                    setOfNotNull(old.lang),
                    old.versionName,
                    null,
                    null,
                    StoreAction.TRUST,
                    true,
                    old.isNsfw,
                    anime = old,
                )
            } +
            available.filter { value ->
                installed.none { it.pkgName == value.pkgName } && untrusted.none { it.pkgName == value.pkgName }
            }
                .distinctBy { it.pkgName to it.store.indexUrl }.map { value ->
                    StoreEntry(
                        "ANIME:${value.pkgName}:${value.store.indexUrl}", value.pkgName, value.name, StoreMedium.ANIME,
                        supportsHome(value.advertisedHomeVersion) &&
                            value.libVersion in AnimeExtensionLoader.SUPPORTED_LIB_VERSIONS,
                        value.sources.map { it.lang }.toSet(),
                        value.versionName,
                        value.store.indexUrl,
                        value.store.name,
                        StoreAction.INSTALL,
                        false,
                        value.isNsfw,
                        anime = value,
                        animeCandidate = value,
                    )
                }
        return result
    }

    fun manga(
        installed: List<MangaExtension.Installed>,
        untrusted: List<MangaExtension.Untrusted>,
        available: List<MangaExtension.Available>,
    ): List<StoreEntry> {
        val candidates = available.groupBy { it.pkgName }
        return installed.map { old ->
            val variants = candidates[old.pkgName].orEmpty().groupBy { it.repoUrl }
                .values.mapNotNull { it.maxByOrNull(MangaExtension.Available::versionCode) }
            val updateCandidate = variants.singleOrNull() ?: variants.singleOrNull { it.repoUrl == old.repoUrl }
            val ready = old.metadata.home in setOf(ExtensionHomeSupport.READY, ExtensionHomeSupport.PARTIAL)
            // Optional Home capabilities do not change the package installation mode.
            val candidate = updateCandidate
            val action = if (old.hasUpdate) StoreAction.UPDATE else StoreAction.OPEN
            StoreEntry(
                "MANGA:${old.pkgName}", old.pkgName, old.name, StoreMedium.MANGA,
                ready ||
                    (
                        action == StoreAction.UPDATE &&
                            supportsHome(candidate?.advertisedHomeVersion)
                        ),
                old.sources.map { it.lang }.toSet(), old.versionName,
                candidate?.repoUrl ?: old.repoUrl, candidate?.repoName ?: old.repoName, action, true, old.isNsfw,
                manga = old, mangaCandidate = candidate,
            )
        } +
            untrusted.map { old ->
                StoreEntry(
                    "MANGA:${old.pkgName}", old.pkgName, old.name, StoreMedium.MANGA, false,
                    setOfNotNull(old.lang),
                    old.versionName,
                    null,
                    null,
                    StoreAction.TRUST,
                    true,
                    old.isNsfw,
                    manga = old,
                )
            } +
            available.filter { value ->
                installed.none { it.pkgName == value.pkgName } && untrusted.none { it.pkgName == value.pkgName }
            }
                .distinctBy { it.pkgName to it.repoUrl }.map { value ->
                    StoreEntry(
                        "MANGA:${value.pkgName}:${value.repoUrl}", value.pkgName, value.name, StoreMedium.MANGA,
                        supportsHome(value.advertisedHomeVersion) &&
                            value.libVersion in MangaExtensionLoader.SUPPORTED_LIB_VERSIONS,
                        value.sources.map { it.lang }.toSet(),
                        value.versionName,
                        value.repoUrl,
                        value.repoName,
                        StoreAction.INSTALL,
                        false,
                        value.isNsfw,
                        manga = value,
                        mangaCandidate = value,
                    )
                }
    }

    fun news(
        installed: List<NewsExtension>,
        available: List<NewsExtensionCatalogue.Entry>,
        updates: List<NewsExtensionCatalogue.Entry>,
    ): List<StoreEntry> =
        installed.map { old ->
            val candidate = updates.singleOrNull { it.packageName == old.packageName }
            StoreEntry(
                "NEWS:${old.packageName}", old.packageName, old.label, StoreMedium.NEWS, old.compatible,
                setOfNotNull(old.source?.language), old.version, candidate?.repository, candidate?.repositoryName,
                when {
                    candidate != null -> StoreAction.UPDATE
                    else -> StoreAction.MANAGE
                },
                true,
                news = old,
                newsCandidate = candidate,
            )
        } +
            available.filter { value -> installed.none { it.packageName == value.packageName } }.map { value ->
                StoreEntry(
                    "NEWS:${value.packageName}:${value.repository}",
                    value.packageName,
                    value.name,
                    StoreMedium.NEWS,
                    true,
                    value.sources.map { it.language }.toSet(),
                    value.versionName,
                    value.repository,
                    value.repositoryName,
                    StoreAction.INSTALL, false, newsCandidate = value,
                )
            }

    fun addons(
        installed: List<AddonExtension>,
        available: List<AddonExtensionCatalogue.Entry>,
        updates: List<AddonExtensionCatalogue.Entry>,
    ): List<StoreEntry> =
        installed.map { old ->
            val candidate = updates.singleOrNull { it.packageName == old.packageName }
            StoreEntry(
                "ADDON:${old.packageName}", old.packageName, old.label, StoreMedium.ADDON, old.compatible,
                emptySet(), old.version, candidate?.repository, candidate?.repositoryName,
                when {
                    candidate != null -> StoreAction.UPDATE
                    old.compatible && !old.trusted -> StoreAction.TRUST
                    old.usable -> StoreAction.OPEN
                    else -> StoreAction.MANAGE
                },
                true, addon = old, addonCandidate = candidate,
            )
        } +
            available.filter { value -> installed.none { it.packageName == value.packageName } }.map { value ->
                StoreEntry(
                    "ADDON:${value.packageName}:${value.repository}", value.packageName, value.name,
                    StoreMedium.ADDON, true, emptySet(), value.versionName, value.repository, value.repositoryName,
                    StoreAction.INSTALL, false, addonCandidate = value,
                )
            }
}

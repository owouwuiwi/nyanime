package eu.kanade.tachiyomi.data.discovery

import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.anime.interactor.GetAnimeIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import tachiyomi.domain.discovery.SourceHomeAccess
import tachiyomi.domain.discovery.SourceHomeGroupAccess
import tachiyomi.domain.discovery.SourceHomeListing
import tachiyomi.domain.discovery.SourceHomeSource
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import java.util.IdentityHashMap

/** Installed capabilities only: no availability scans or network requests during discovery. */
class ExtensionHomeRegistry(
    private val manager: AnimeSourceManager,
    private val extensions: AnimeExtensionManager,
    private val visibility: DiscoverySourceService,
    private val preferences: SourcePreferences,
    private val base: BasePreferences,
    private val incognito: GetAnimeIncognitoState,
    private val manifests: ExtensionHomeManifestReader,
) {
    private val cached = IdentityHashMap<AnimeExtension.Installed, List<ExtensionHomeManifest>>()

    private fun changes() = combine(
        combine(manager.sources, manager.isInitialized, extensions.installedExtensionsFlow) { _, _, _ -> Unit },
        combine(
            preferences.disabledAnimeSources().changes(),
            preferences.enabledLanguages().changes(),
            preferences.showNsfwSource().changes(),
        ) { _, _, _ -> Unit },
        combine(
            base.downloadedOnly().changes(),
            base.incognitoMode().changes(),
            preferences.incognitoAnimeExtensions().changes(),
        ) { _, _, _ -> Unit },
    ) { _, _, _ -> Unit }

    fun observe() = changes().map { current() }
        .distinctUntilChanged().flowOn(Dispatchers.IO)

    fun observeAccess(key: String) = changes().map { access(key) }
        .distinctUntilChanged().flowOn(Dispatchers.IO)

    fun observeGroup(id: String) = changes().map { groupAccess(id) }
        .distinctUntilChanged().flowOn(Dispatchers.IO)

    @Synchronized
    fun current(): SourceHomeListing {
        if (!manager.isInitialized.value) return SourceHomeListing()
        val installed = extensions.installedExtensionsFlow.value
        cached.keys.removeAll { previous -> installed.none { it === previous } }
        val homes = installed.flatMap { extension ->
            try {
                cached.getOrPut(extension) { manifests.read(extension) }.mapNotNull { manifest ->
                    val source = extension.sources.singleOrNull {
                        it.name == manifest.source.name &&
                            it.lang == manifest.source.lang &&
                            manager.get(it.id) === it &&
                            visibility.isEnabled(it)
                    } ?: return@mapNotNull null
                    val filters = source.getFilterList()
                    SourceHomeSource(
                        id = source.id,
                        revision = "${extension.versionCode}:${extension.versionName}:${manifest.hashCode()}",
                        sections = ExtensionHomeFilters.sections(manifest, filters),
                        categories = ExtensionHomeFilters.categories(manifest, filters),
                        key = "${extension.pkgName}:${manifest.id}:${source.id}",
                        title = manifest.title,
                        sourceName = source.name,
                        language = source.lang,
                        search = ExtensionHomeFilters.search(manifest, filters),
                        homeId = manifest.id,
                        primary = manifest.primary,
                        browseFilters = ExtensionHomeFilters.browseFilters(manifest, filters),
                        random = ExtensionHomeFilters.random(manifest, filters),
                        randomEpisode = ExtensionHomeFilters.randomEpisode(manifest, filters),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            } catch (_: LinkageError) {
                // An incompatible optional capability must not take the Home down with it.
                emptyList()
            }
        }.distinctBy { it.key }.sortedWith(compareBy({ it.title }, { it.key }))
        return SourceHomeListing(loading = false, homes = homes)
    }

    fun access(key: String): SourceHomeAccess {
        val listing = current()
        val source = listing.homes.firstOrNull { it.key == key }
        return SourceHomeAccess(
            source = source,
            loading = listing.loading,
            offline = base.downloadedOnly().get(),
            isPrivate = incognito.await(source?.id),
        )
    }

    fun groupAccess(id: String): SourceHomeGroupAccess {
        val listing = current()
        val group = listing.groups.firstOrNull { it.id == id }
        val offline = base.downloadedOnly().get()
        return SourceHomeGroupAccess(
            group = group,
            providers = group?.providers.orEmpty().map { source ->
                SourceHomeAccess(source = source, offline = offline, isPrivate = incognito.await(source.id))
            },
            loading = listing.loading,
            offline = offline,
        )
    }
}

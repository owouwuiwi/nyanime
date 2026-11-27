package eu.kanade.tachiyomi.data.discovery

import nyanime.extension.internal.layerFilters

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
    private val layers: eu.kanade.tachiyomi.data.layer.MangaLayerRegistry? = null,
) {
    private val cached = IdentityHashMap<AnimeExtension.Installed, List<ExtensionHomeManifest>>()

    private fun changes() = combine(
        combine(
            manager.sources, manager.isInitialized, extensions.installedExtensionsFlow,
            layers?.revision ?: kotlinx.coroutines.flow.flowOf(0L),
        ) { _, _, _, _ -> Unit },
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
                extension.sources.filter {
                    manager.get(it.id) === it && visibility.isEnabled(it)
                }.flatMap { source ->
                    val native = cached.getOrPut(extension) { manifests.read(extension) }.filter {
                        it.source.name == source.name && it.source.lang == source.lang
                    }
                    val overlay = layers?.homes(source).orEmpty()
                    if (native.isEmpty() && overlay.isEmpty()) return@flatMap emptyList()
                    val filters = source.layerFilters()
                    val declarations = overlay.filter { ExtensionHomeFilters.sections(it, filters).isNotEmpty() }.ifEmpty { native }
                    declarations.map { manifest ->
                    SourceHomeSource(
                        id = source.id,
                        revision = "${extension.versionCode}:${extension.versionName}:${manifest.hashCode()}:${layers?.homeRevision(source).orEmpty()}",
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
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            } catch (_: LinkageError) {
                // An incompatible optional capability must not take the Home down with it.
                emptyList()
            }
        }
        val orderedHomes = homes.distinctBy { it.key }.sortedWith(compareBy({ it.title }, { it.key }))
        val declared = homes.map { it.id }.toSet()
        val nativeFallback = homes.none { it.primary } &&
            installed.any { extension ->
                extension.sources.any { it.id !in declared && manager.get(it.id) === it && visibility.isEnabled(it) }
            }
        return SourceHomeListing(loading = false, homes = orderedHomes, nativeFallback = nativeFallback)
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

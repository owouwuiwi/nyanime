package eu.kanade.tachiyomi.data.discovery

import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.manga.interactor.GetMangaIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import eu.kanade.tachiyomi.source.CatalogueSource
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
import tachiyomi.domain.source.manga.service.MangaSourceManager
import java.util.IdentityHashMap

/** Installed capabilities only: no availability scans or network requests during discovery. */
class MangaHomeRegistry(
    private val manager: MangaSourceManager,
    private val extensions: MangaExtensionManager,
    private val preferences: SourcePreferences,
    private val base: BasePreferences,
    private val incognito: GetMangaIncognitoState,
    private val manifests: MangaHomeManifestReader,
    private val uiPreferences: UiPreferences,
) {
    private val cached = IdentityHashMap<MangaExtension.Installed, List<ExtensionHomeManifest>>()

    private fun changes() = combine(
        combine(manager.catalogueSources, manager.isInitialized, extensions.installedExtensionsFlow) { _, _, _ ->
            Unit
        },
        combine(
            preferences.disabledMangaSources().changes(),
            preferences.enabledLanguages().changes(),
            preferences.showNsfwSource().changes(),
            uiPreferences.showMangaInOtherLanguages().changes(),
        ) { _, _, _, _ -> Unit },
        combine(
            base.downloadedOnly().changes(),
            base.incognitoMode().changes(),
            preferences.incognitoMangaExtensions().changes(),
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
                    val source = extension.sources.filterIsInstance<CatalogueSource>().singleOrNull {
                        it.name == manifest.source.name &&
                            it.lang == manifest.source.lang &&
                            manager.get(it.id) === it &&
                            isEnabled(it.id, it.lang, extension)
                    } ?: return@mapNotNull null
                    val filters = source.getFilterList()
                    SourceHomeSource(
                        id = source.id,
                        revision = "${extension.versionCode}:${extension.versionName}:${manifest.hashCode()}",
                        sections = MangaHomeFilters.sections(manifest, filters),
                        categories = MangaHomeFilters.categories(manifest, filters),
                        key = "${extension.pkgName}:${manifest.id}:${source.id}",
                        title = manifest.title,
                        sourceName = source.name,
                        language = source.lang,
                        search = MangaHomeFilters.search(manifest, filters),
                        homeId = manifest.id,
                        primary = manifest.primary,
                        browseFilters = MangaHomeFilters.browseFilters(manifest, filters),
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

    /** Local classification uses already validated declarations, independently of feed visibility. */
    @Synchronized
    fun declaredHomeIds(sourceId: Long): Set<String> {
        val source = manager.get(sourceId) as? CatalogueSource ?: return emptySet()
        val extension = extensions.installedExtensionsFlow.value.firstOrNull { installed ->
            installed.sources.any { it === source }
        } ?: return emptySet()
        return cached[extension].orEmpty()
            .filter { it.source.name == source.name && it.source.lang == source.lang }
            .map { it.id }.toSet()
    }

    private fun isEnabled(id: Long, language: String, extension: MangaExtension.Installed): Boolean =
        id.toString() !in preferences.disabledMangaSources().get() &&
            (language == "it" || uiPreferences.showMangaInOtherLanguages().get()) &&
            (
                language in preferences.enabledLanguages().get() ||
                    (uiPreferences.showMangaInOtherLanguages().get() && language != "it")
                ) &&
            (preferences.showNsfwSource().get() || !extension.isNsfw)

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

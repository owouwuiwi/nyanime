package eu.kanade.domain.extension.manga.interactor

import eu.kanade.domain.extension.manga.model.MangaExtensions
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class GetMangaExtensionsByType(
    private val preferences: SourcePreferences,
    private val extensionManager: MangaExtensionManager,
) {

    fun subscribe(): Flow<MangaExtensions> {
        val showNsfwSources = preferences.showNsfwSource().get()

        return combine(
            preferences.enabledLanguages().changes(),
            extensionManager.installedExtensionsFlow,
            extensionManager.untrustedExtensionsFlow,
            extensionManager.availableExtensionsFlow,
        ) { enabledLanguages, _installed, _untrusted, _available ->
            val (updates, installed) = _installed
                .filter { (showNsfwSources || !it.isNsfw) }
                .sortedWith(
                    compareBy<MangaExtension.Installed> { !it.isObsolete }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
                )
                .partition { it.hasUpdate }

            val untrusted = _untrusted
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

            val available = _available
                .filter { extension ->
                    _installed.none { it.pkgName == extension.pkgName } &&
                        _untrusted.none { it.pkgName == extension.pkgName } &&
                        (showNsfwSources || !extension.isNsfw)
                }
                .filter { ext -> ext.lang in enabledLanguages || ext.sources.any { it.lang in enabledLanguages } }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

            MangaExtensions(updates, installed, available, untrusted)
        }
    }
}

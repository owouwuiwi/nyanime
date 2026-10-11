package eu.kanade.presentation.more.settings.screen.browse

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.extension.ExtensionCatalogueLink
import eu.kanade.domain.extension.ExtensionCatalogueTrust
import eu.kanade.domain.extension.ImportExtensionCatalogue
import eu.kanade.domain.extension.UnifiedExtensionCatalogue
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.catalogueRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import mihon.domain.extension.anime.repository.AnimeExtensionStoreRepository
import mihon.domain.extensionrepo.manga.repository.MangaExtensionRepoRepository
import tachiyomi.core.common.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ExtensionCatalogueScreenModel(private val url: String) :
    StateScreenModel<ExtensionCatalogueScreenModel.State>(State.Loading) {
    private val anime: AnimeExtensionStoreRepository = Injekt.get()
    private val manga: MangaExtensionRepoRepository = Injekt.get()

    init {
        load()
    }

    fun load() {
        mutableState.value = State.Loading
        screenModelScope.launchIO {
            val link = ExtensionCatalogueLink.parse(url)
            try {
                val network: NetworkHelper = Injekt.get()
                val indexUrl = when (val validatedLink = requireNotNull(link)) {
                    is ExtensionCatalogueLink.Index -> validatedLink.url
                    is ExtensionCatalogueLink.DriveFolder -> DriveCatalogueResolver.resolve(validatedLink, network.client)
                }
                val catalogue = network.client.newCall(catalogueRequest(indexUrl)).awaitSuccess().use { response ->
                    val source = response.body.source()
                    require(!source.request(UnifiedExtensionCatalogue.MAX_BYTES + 1))
                    UnifiedExtensionCatalogue.parse(source.readUtf8())
                }
                val previous =
                    anime.getAll().filter {
                        it.indexUrl in catalogue.replaces && it.indexUrl != indexUrl
                    }.map { it.name } +
                        manga.getAll().filter {
                            it.baseUrl != indexUrl &&
                                (it.baseUrl in catalogue.replaces || it.signingKeyFingerprint == catalogue.signingKey)
                        }.map { it.name }
                mutableState.value = State.Preview(catalogue, previous.distinct(), indexUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.value = State.Failed(
                    driveFolder = link is ExtensionCatalogueLink.DriveFolder,
                    driveFile = link is ExtensionCatalogueLink.Index && link.isDrive,
                )
            }
        }
    }

    fun trustExtensions(enabled: Boolean) {
        val preview = state.value as? State.Preview ?: return
        if (!preview.processing) mutableState.value = preview.copy(trustExtensions = enabled)
    }

    fun add() {
        val preview = state.value as? State.Preview ?: return
        if (preview.processing) return
        mutableState.value = preview.copy(processing = true, failed = false)
        screenModelScope.launchIO {
            try {
                ImportExtensionCatalogue(anime, manga, ExtensionCatalogueTrust(Injekt.get()))
                    .await(preview.indexUrl, preview.catalogue, preview.trustExtensions)
                mutableState.value = State.Done(preview.catalogue.name)
                screenModelScope.launchIO { Injekt.get<AnimeExtensionManager>().findAvailableExtensions() }
                screenModelScope.launchIO { Injekt.get<MangaExtensionManager>().findAvailableExtensions() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.update { preview.copy(processing = false, failed = true) }
            }
        }
    }

    sealed interface State {
        data object Loading : State
        data class Failed(val driveFolder: Boolean, val driveFile: Boolean) : State
        data class Preview(
            val catalogue: UnifiedExtensionCatalogue,
            val previous: List<String>,
            val indexUrl: String,
            val processing: Boolean = false,
            val failed: Boolean = false,
            val trustExtensions: Boolean = false,
        ) : State
        data class Done(val name: String) : State
    }
}

package eu.kanade.presentation.more.settings.screen.browse

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.extension.ExtensionCatalogueAuthorities
import eu.kanade.domain.extension.ImportExtensionCatalogue
import eu.kanade.domain.extension.UnifiedExtensionCatalogue
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
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
            try {
                require(UnifiedExtensionCatalogue.validUrl(url))
                val network: NetworkHelper = Injekt.get()
                val catalogue = network.client.newCall(GET(url)).awaitSuccess().use { response ->
                    val source = response.body.source()
                    require(!source.request(UnifiedExtensionCatalogue.MAX_BYTES + 1))
                    UnifiedExtensionCatalogue.parse(source.readUtf8())
                }
                val previous =
                    anime.getAll().filter { it.indexUrl in catalogue.replaces && it.indexUrl != url }.map { it.name } +
                        manga.getAll().filter {
                            it.baseUrl != url &&
                                (it.baseUrl in catalogue.replaces || it.signingKeyFingerprint == catalogue.signingKey)
                        }.map { it.name }
                mutableState.value = State.Preview(catalogue, previous.distinct())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.value = State.Failed
            }
        }
    }

    fun add() {
        val preview = state.value as? State.Preview ?: return
        if (preview.processing) return
        mutableState.value = preview.copy(processing = true, failed = false)
        screenModelScope.launchIO {
            try {
                ImportExtensionCatalogue(anime, manga, ExtensionCatalogueAuthorities(Injekt.get()))
                    .await(url, preview.catalogue)
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
        data object Failed : State
        data class Preview(
            val catalogue: UnifiedExtensionCatalogue,
            val previous: List<String>,
            val processing: Boolean = false,
            val failed: Boolean = false,
        ) : State
        data class Done(val name: String) : State
    }
}

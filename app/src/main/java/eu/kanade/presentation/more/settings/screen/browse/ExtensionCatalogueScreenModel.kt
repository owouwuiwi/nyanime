package eu.kanade.presentation.more.settings.screen.browse

import android.app.Application
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.extension.ExtensionCatalogueLink
import eu.kanade.domain.extension.ExtensionCatalogueTrust
import eu.kanade.domain.extension.ImportExtensionCatalogue
import eu.kanade.domain.extension.UnifiedExtensionCatalogue
import eu.kanade.domain.extension.UnifiedExtensionCatalogue.LinkedCatalogue
import eu.kanade.domain.extension.VerifiedLinkedCatalogue
import eu.kanade.tachiyomi.data.extensionstore.ExtensionStoreRepository
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.catalogueRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import mihon.data.extension.anime.service.AnimeExtensionStoreService
import mihon.domain.extension.anime.repository.AnimeExtensionStoreRepository
import mihon.domain.extensionrepo.manga.repository.MangaExtensionRepoRepository
import mihon.domain.extensionrepo.service.ExtensionRepoService
import tachiyomi.core.common.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.coroutines.coroutineContext

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
                val animeStores = anime.getAll()
                val mangaRepos = manga.getAll()
                val configuredSigners = animeStores.map { it.indexUrl to it.signingKey } +
                    mangaRepos.map { it.baseUrl to it.signingKeyFingerprint }
                val linkedCatalogues = coroutineScope {
                    catalogue.catalogues.map { reference ->
                        async {
                            val conflicts = configuredSigners.any { (configuredUrl, signer) ->
                                reference.isAt(configuredUrl) && !reference.signingKey.equals(signer, ignoreCase = true)
                            }
                            if (reference.isAt(indexUrl) || conflicts) {
                                LinkedCataloguePreview.Mismatch(reference)
                            } else {
                                verifyLinkedCatalogue(reference)
                            }
                        }
                    }.awaitAll()
                }
                val previous =
                    animeStores.filter {
                        it.indexUrl in catalogue.replaces && it.indexUrl != indexUrl
                    }.map { it.name } +
                        mangaRepos.filter {
                            it.baseUrl != indexUrl &&
                                (it.baseUrl in catalogue.replaces || it.signingKeyFingerprint == catalogue.signingKey)
                        }.map { it.name }
                mutableState.value = State.Preview(catalogue, previous.distinct(), indexUrl, linkedCatalogues)
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

    private suspend fun verifyLinkedCatalogue(reference: LinkedCatalogue): LinkedCataloguePreview =
        try {
            when (reference.type) {
                LinkedCatalogue.Type.ANIME -> {
                    val store = Injekt.get<AnimeExtensionStoreService>().fetch(reference.url).getOrThrow()
                    verifiedLink(reference, store.name) { VerifiedLinkedCatalogue.Anime(reference, store) }
                }
                LinkedCatalogue.Type.MANGA -> {
                    val repo = Injekt.get<ExtensionRepoService>().fetchRepoDetails(reference.url)
                    coroutineContext.ensureActive()
                    if (repo == null) {
                        LinkedCataloguePreview.Unavailable(reference)
                    } else {
                        verifiedLink(reference, repo.name) { VerifiedLinkedCatalogue.Manga(reference, repo) }
                    }
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            LinkedCataloguePreview.Unavailable(reference)
        }

    private inline fun verifiedLink(
        reference: LinkedCatalogue,
        name: String,
        verify: () -> VerifiedLinkedCatalogue,
    ): LinkedCataloguePreview = try {
        LinkedCataloguePreview.Verified(verify())
    } catch (_: IllegalArgumentException) {
        LinkedCataloguePreview.Mismatch(reference, name.takeIf(String::isNotBlank))
    }

    fun trustExtensions(enabled: Boolean) {
        val preview = state.value as? State.Preview ?: return
        if (preview.canAdd) mutableState.value = preview.copy(trustExtensions = enabled)
    }

    fun add() {
        val preview = state.value as? State.Preview ?: return
        if (!preview.canAdd) return
        val verified = preview.linkedCatalogues.filterIsInstance<LinkedCataloguePreview.Verified>().map { it.catalogue }
        mutableState.value = preview.copy(processing = true, failed = false)
        screenModelScope.launchIO {
            try {
                ImportExtensionCatalogue(anime, manga, ExtensionCatalogueTrust(Injekt.get()))
                    .await(preview.indexUrl, preview.catalogue, preview.trustExtensions, linkedCatalogues = verified)
                ExtensionStoreRepository.get(Injekt.get<Application>()).onCataloguesChanged()
                mutableState.value = State.Done(preview.catalogue.name, verified.isNotEmpty())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.update { preview.copy(processing = false, failed = true) }
            }
        }
    }

    sealed interface LinkedCataloguePreview {
        val reference: LinkedCatalogue
        val name: String? get() = null

        data class Verified(val catalogue: VerifiedLinkedCatalogue) : LinkedCataloguePreview {
            override val reference get() = catalogue.reference
            override val name get() = catalogue.name
        }

        data class Mismatch(
            override val reference: LinkedCatalogue,
            override val name: String? = null,
        ) : LinkedCataloguePreview

        data class Unavailable(override val reference: LinkedCatalogue) : LinkedCataloguePreview
    }

    sealed interface State {
        data object Loading : State
        data class Failed(val driveFolder: Boolean, val driveFile: Boolean) : State
        data class Preview(
            val catalogue: UnifiedExtensionCatalogue,
            val previous: List<String>,
            val indexUrl: String,
            val linkedCatalogues: List<LinkedCataloguePreview>,
            val processing: Boolean = false,
            val failed: Boolean = false,
            val trustExtensions: Boolean = false,
        ) : State {
            val linksVerified get() = linkedCatalogues.all { it is LinkedCataloguePreview.Verified }
            val canAdd get() = !processing && linksVerified
        }
        data class Done(val name: String, val includesLinks: Boolean) : State
    }
}

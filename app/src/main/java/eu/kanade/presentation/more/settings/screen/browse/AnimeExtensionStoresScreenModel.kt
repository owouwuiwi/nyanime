package eu.kanade.presentation.more.settings.screen.browse

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import mihon.domain.extension.anime.interactor.AddAnimeExtensionStore
import mihon.domain.extension.anime.interactor.GetAnimeExtensionStores
import mihon.domain.extension.anime.interactor.RemoveAnimeExtensionStore
import mihon.domain.extension.anime.interactor.UpdateAnimeExtensionStores
import mihon.domain.extension.anime.model.AnimeExtensionStore
import tachiyomi.core.common.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeExtensionStoresScreenModel(
    private val getExtensionStores: GetAnimeExtensionStores = Injekt.get(),
    private val addExtensionStore: AddAnimeExtensionStore = Injekt.get(),
    private val removeExtensionStore: RemoveAnimeExtensionStore = Injekt.get(),
    private val updateExtensionStores: UpdateAnimeExtensionStores = Injekt.get(),
    private val extensionManager: AnimeExtensionManager = Injekt.get(),
) : StateScreenModel<AnimeExtensionStoreScreenState>(AnimeExtensionStoreScreenState.Loading) {
    private var pendingDialog: AnimeExtensionStoreDialog? = null

    private inline fun updateSuccessState(
        func: (AnimeExtensionStoreScreenState.Success) -> AnimeExtensionStoreScreenState.Success,
    ) {
        mutableState.update {
            when (it) {
                AnimeExtensionStoreScreenState.Loading -> it
                is AnimeExtensionStoreScreenState.Success -> func(it)
            }
        }
    }

    init {
        screenModelScope.launchIO {
            getExtensionStores.subscribe()
                .collectLatest { stores ->
                    mutableState.update {
                        when (it) {
                            AnimeExtensionStoreScreenState.Loading -> AnimeExtensionStoreScreenState.Success(
                                stores = stores,
                                dialog = pendingDialog?.let { dialog ->
                                    if (dialog is AnimeExtensionStoreDialog.Confirm) {
                                        dialog.copy(alreadyExists = stores.any { it.indexUrl == dialog.url })
                                    } else {
                                        dialog
                                    }
                                },
                            )
                            is AnimeExtensionStoreScreenState.Success -> it.copy(stores = stores)
                        }
                    }
                }
        }
    }

    /**
     * Creates and adds a new repo to the database.
     *
     * @param baseUrl The baseUrl of the repo to create.
     */
    fun createRepo(baseUrl: String) {
        val confirmation =
            (state.value as? AnimeExtensionStoreScreenState.Success)?.dialog as? AnimeExtensionStoreDialog.Confirm
                ?: return
        if (confirmation.url != baseUrl || confirmation.processing) return
        updateSuccessState {
            it.copy(
                dialog = when (it.dialog) {
                    is AnimeExtensionStoreDialog.Confirm -> it.dialog.copy(processing = true)
                    else -> it.dialog
                },
            )
        }
        screenModelScope.launchIO {
            addExtensionStore(baseUrl)
                .onSuccess {
                    extensionManager.findAvailableExtensions()
                    dismissDialog()
                }
                .onFailure { throwable ->
                    updateSuccessState {
                        it.copy(
                            dialog = when (it.dialog) {
                                is AnimeExtensionStoreDialog.Confirm -> it.dialog.copy(
                                    processing = false,
                                    errorMessage = throwable.message ?: "unknown error",
                                )
                                else -> it.dialog
                            },
                        )
                    }
                }
        }
    }

    /**
     * Refreshes information for each repository.
     */
    fun refreshRepos() {
        val status = state.value

        if (status is AnimeExtensionStoreScreenState.Success) {
            screenModelScope.launchIO {
                updateExtensionStores()
            }
        }
    }

    /**
     * Deletes the given repo from the database
     */
    fun deleteRepo(baseUrl: String) {
        screenModelScope.launchIO {
            removeExtensionStore(baseUrl)
            eu.kanade.domain.extension.ExtensionCatalogueTrust(Injekt.get()).remove(baseUrl)
            extensionManager.findAvailableExtensions()
        }
    }

    fun addFromDeeplink(storeIndexUrl: String) {
        val stores = (state.value as? AnimeExtensionStoreScreenState.Success)?.stores.orEmpty()
        showDialog(
            AnimeExtensionStoreDialog.Confirm(
                url = storeIndexUrl,
                alreadyExists = stores.any { it.indexUrl == storeIndexUrl },
            ),
        )
    }

    fun showDialog(dialog: AnimeExtensionStoreDialog) {
        pendingDialog = dialog
        updateSuccessState { state ->
            state.copy(dialog = dialog)
        }
    }

    fun dismissDialog() {
        pendingDialog = null
        updateSuccessState {
            it.copy(dialog = null)
        }
    }
}

sealed class AnimeExtensionStoreDialog {
    data object LinkHelp : AnimeExtensionStoreDialog()
    data class Delete(val store: AnimeExtensionStore) : AnimeExtensionStoreDialog()
    data class Confirm(
        val url: String,
        val alreadyExists: Boolean = false,
        val processing: Boolean = false,
        val errorMessage: String? = null,
    ) : AnimeExtensionStoreDialog()
}

sealed class AnimeExtensionStoreScreenState {

    @Immutable
    data object Loading : AnimeExtensionStoreScreenState()

    @Immutable
    data class Success(
        val stores: List<AnimeExtensionStore>,
        val dialog: AnimeExtensionStoreDialog? = null,
    ) : AnimeExtensionStoreScreenState() {

        val isEmpty: Boolean
            get() = stores.isEmpty()
    }
}

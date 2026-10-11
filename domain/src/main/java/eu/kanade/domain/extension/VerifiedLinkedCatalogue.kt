package eu.kanade.domain.extension

import eu.kanade.domain.extension.UnifiedExtensionCatalogue.LinkedCatalogue
import mihon.domain.extension.anime.model.AnimeExtensionStore
import mihon.domain.extensionrepo.model.ExtensionRepo

/** Metadata read from the linked catalogue and matched against the publisher's declaration. */
sealed interface VerifiedLinkedCatalogue {
    val reference: LinkedCatalogue
    val name: String

    data class Anime(override val reference: LinkedCatalogue, val store: AnimeExtensionStore) : VerifiedLinkedCatalogue {
        override val name: String get() = store.name

        init {
            require(reference.type == LinkedCatalogue.Type.ANIME)
            requireMatchingReference(reference, store.indexUrl, store.signingKey, store.name)
        }
    }

    data class Manga(override val reference: LinkedCatalogue, val repo: ExtensionRepo) : VerifiedLinkedCatalogue {
        override val name: String get() = repo.name

        init {
            require(reference.type == LinkedCatalogue.Type.MANGA)
            requireMatchingReference(reference, repo.baseUrl, repo.signingKeyFingerprint, repo.name)
        }
    }
}

private fun requireMatchingReference(reference: LinkedCatalogue, url: String, signer: String, name: String) {
    require(url == reference.url) { "Linked catalogue URL changed" }
    require(signer.equals(reference.signingKey, ignoreCase = true)) { "Linked catalogue signing key differs" }
    require(name.isNotBlank()) { "Linked catalogue name is missing" }
}

package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupExtensionRepos
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaExtensionRepoRestorer
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import mihon.domain.extensionrepo.manga.interactor.GetMangaExtensionRepo
import mihon.domain.extensionrepo.model.ExtensionRepo
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tachiyomi.data.handlers.manga.MangaDatabaseHandler

class MangaExtensionRepoRestorerTest {
    private val existing = ExtensionRepo(
        "https://catalog.example/index",
        "Catalogue",
        null,
        "https://catalog.example",
        "trusted-key",
    )
    private val handler = mockk<MangaDatabaseHandler>()
    private val repos = mockk<GetMangaExtensionRepo>()
    private val restorer = MangaExtensionRepoRestorer(handler, repos)

    private fun backup(url: String = existing.baseUrl, key: String = existing.signingKeyFingerprint) =
        BackupExtensionRepos(url, "Saved catalogue", null, existing.website, key)

    @Test fun restoringTheSameTrustedCatalogueDoesNotProduceAnErrorOrInsertItAgain() = runBlocking {
        coEvery { repos.getAll() } returns listOf(existing)
        restorer(backup())
        verify { handler wasNot Called }
    }

    @Test fun aDifferentSigningKeyCannotReplaceATrustedCatalogue() {
        coEvery { repos.getAll() } returns listOf(existing)
        assertThrows(IllegalStateException::class.java) { runBlocking { restorer(backup(key = "untrusted-key")) } }
        verify { handler wasNot Called }
    }

    @Test fun theSameKeyOnADifferentAddressIsNotSilentlyAccepted() {
        coEvery { repos.getAll() } returns listOf(existing)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { restorer(backup(url = "https://other.example/index")) }
        }
        verify { handler wasNot Called }
    }
}

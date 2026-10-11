package eu.kanade.tachiyomi.data.extensionstore

import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StoreApkRevisionTest {
    private val candidate = MangaExtension.Available(
        name = "Example extension",
        pkgName = "test.extension.example",
        versionName = "1.6.65",
        versionCode = 65,
        libVersion = 1.6,
        lang = "en",
        isNsfw = false,
        sources = emptyList(),
        apkUrl = "https://example.invalid/download.apk",
        iconUrl = "https://example.invalid/icon.png",
        repoUrl = "https://example.invalid/index.json",
    )

    @Test fun `Android requirement applies only to the verified catalogue revision`() {
        val current = entry()
        val revision = requireNotNull(StoreApkRevision.from(current))
        assertTrue(current.canImport)
        assertTrue(revision.matches(current))
        assertTrue(revision.matches(current.copy(id = "installed", installed = true)))
        listOf(
            candidate.copy(versionCode = 66),
            candidate.copy(pkgName = "test.extension.other"),
            candidate.copy(apkUrl = "https://example.invalid/replaced.apk"),
            candidate.copy(repoUrl = "https://other.invalid/index.json"),
            candidate.copy(expectedSigner = "a".repeat(64)),
            candidate.copy(apkSha256 = "b".repeat(64)),
        ).forEach { assertFalse(revision.matches(entry(it))) }
    }

    @Test fun `a bridge or row without a downloadable candidate cannot request the Android installer`() {
        val current = entry()
        assertNull(StoreApkRevision.from(current.copy(medium = StoreMedium.MANGA_LAYER)))
        assertNull(StoreApkRevision.from(current.copy(mangaCandidate = null)))
    }

    private fun entry(available: MangaExtension.Available = candidate): StoreEntry =
        StoreCatalogue.manga(emptyList(), emptyList(), listOf(available)).single()
}

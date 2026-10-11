package eu.kanade.tachiyomi.data.extensionstore

import eu.kanade.tachiyomi.data.layer.MangaLayerDeclaration
import eu.kanade.tachiyomi.data.layer.MangaLayerTarget
import eu.kanade.tachiyomi.data.rin.RinEntry
import eu.kanade.tachiyomi.data.rin.RinManifest
import eu.kanade.tachiyomi.data.rin.RinRecord
import eu.kanade.tachiyomi.data.rin.RinSource
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import mihon.domain.extension.anime.model.AnimeExtensionStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImportedExtensionUpdatesTest {
    private val signer = "a".repeat(64)
    private val otherSigner = "b".repeat(64)
    private val origin = "https://example.invalid/index.json"
    private val mirror = "https://mirror.invalid/index.json"
    private val manifest = RinManifest(
        1, "test.extension.anime", "Test anime", 1, "16.1", "anime", 16.0,
        entryPoints = listOf("test.extension.anime.Source"),
        sources = listOf(RinSource(901, "Test source", "it")),
        hashes = emptyMap(),
    )
    private val installed = RinRecord(manifest, signer, "c".repeat(64), origin, "old.apk", format = "apk")
    private val offer = RinEntry(
        manifest.copy(versionCode = 2, versionName = "16.2"), "https://example.invalid/next.apk",
        "d".repeat(64), signer, origin, format = "apk",
    )

    private fun update(record: RinRecord = installed, candidate: RinEntry = offer) =
        RinStoreCatalogue.entries(listOf(candidate), listOf(record), emptyList(), emptyList()).single()

    @Test fun `a private trusted APK can update only in its pinned index and with its approved signer`() {
        val entry = update()
        assertTrue(ImportedExtensionUpdates.eligible(entry))
        assertFalse(ImportedExtensionUpdates.eligible(entry.copy(installed = false)))
        assertFalse(ImportedExtensionUpdates.eligible(entry.copy(repository = mirror)))
        assertFalse(ImportedExtensionUpdates.eligible(entry.copy(rinCandidate = offer.copy(repository = mirror))))
        assertFalse(ImportedExtensionUpdates.eligible(entry.copy(rinCandidate = offer.copy(manifest = manifest))))
        assertFalse(ImportedExtensionUpdates.eligible(entry.copy(rinCandidate = offer.copy(requiresAndroid = true))))
        assertFalse(ImportedExtensionUpdates.eligible(entry.copy(rinRecord = installed.copy(repository = ""))))
        val rotated = installed.copy(signerHistory = setOf(signer, otherSigner))
        assertFalse(ImportedExtensionUpdates.eligible(entry.copy(rinRecord = rotated, rinCandidate = offer.copy(signer = otherSigner))))
    }

    @Test fun `an opt-out that arrives while preparing replacement is respected`() {
        val entry = update()
        assertFalse(ImportedExtensionUpdates.eligible(entry.copy(rinRecord = installed.copy(automaticUpdates = false))))
        assertFalse(ImportedExtensionUpdates.matchesInstalled(entry, installed.copy(automaticUpdates = false)))
        assertFalse(ImportedExtensionUpdates.matchesInstalled(entry, installed.copy(sha256 = "e".repeat(64))))
        assertFalse(ImportedExtensionUpdates.matchesInstalled(entry, installed.copy(isShared = true)))
        assertFalse(ImportedExtensionUpdates.matchesInstalled(entry, installed.copy(trusted = false)))
        assertTrue(ImportedExtensionUpdates.matchesInstalled(entry, installed.copy(enabled = false)))
    }

    @Test fun `bridge updates require the already approved scope and cannot add executable hooks`() {
        val bridge = RinManifest(
            2, "test.extension.layer", "Test bridge", 1, "1.0", "layer", 1.0,
            entryPoints = emptyList(), sources = emptyList(), hashes = emptyMap(),
            mangaLayer = MangaLayerDeclaration(
                1,
                listOf(MangaLayerTarget(manifest.packageName, 901, signer, 1, capabilities = setOf("home"), medium = "anime")),
            ),
        )
        val old = installed.copy(manifest = bridge)
        val allowed = offer.copy(manifest = bridge.copy(versionCode = 2, versionName = "1.1"))
        val entry = update(old, allowed)
        assertTrue(ImportedExtensionUpdates.eligible(entry))
        val declaration = requireNotNull(allowed.manifest.mangaLayer)
        val wider = allowed.copy(manifest = allowed.manifest.copy(
            entryPoints = listOf("test.extension.layer.Factory"),
            mangaLayer = declaration.copy(targets = listOf(declaration.targets.single().copy(capabilities = setOf("home", "catalog")))),
        ))
        assertFalse(ImportedExtensionUpdates.eligible(entry.copy(rinCandidate = wider)))
    }

    @Test fun `normal index candidate follows the imported origin rather than the inventory mirror`() {
        val catalogue = AnimeExtensionStore(
            origin, "Test catalogue", "TEST", signer, AnimeExtensionStore.Contact("https://example.invalid", null), false, null,
        )
        val other = catalogue.copy(indexUrl = mirror)
        val anime = AnimeExtension.Installed(
            manifest.name, manifest.packageName, "16.1", 1, 16.0, "it", false, false,
            null, emptyList(), null, isShared = false, store = other,
        )
        val localOffer = AnimeExtension.Available(
            manifest.name, manifest.packageName, "16.2", 2, 16.0, "it", false, false,
            sources = emptyList(), apkUrl = offer.url, iconUrl = "https://example.invalid/icon.png", store = catalogue,
        )
        val fromMirror = localOffer.copy(store = other, versionCode = 3)
        fun entry(candidates: List<AnimeExtension.Available>) = StoreCatalogue.anime(
            listOf(anime), emptyList(), candidates, mapOf(manifest.packageName to origin),
        ).single()
        val selected = entry(listOf(fromMirror, localOffer))
        assertEquals(localOffer, selected.animeCandidate)
        assertNull(entry(listOf(fromMirror)).animeCandidate)
        assertTrue(ImportedExtensionUpdates.eligible(selected.copy(action = StoreAction.UPDATE, rinRecord = installed)))
        assertFalse(ImportedExtensionUpdates.eligible(selected.copy(
            action = StoreAction.UPDATE, rinRecord = installed, anime = anime.copy(keepVersion = true),
        )))
    }
}

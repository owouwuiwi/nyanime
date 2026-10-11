package eu.kanade.tachiyomi.data.extensionstore

import eu.kanade.domain.extension.ExtensionHomeSupport
import eu.kanade.domain.extension.ExtensionPackageMetadata
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import eu.kanade.tachiyomi.source.MangaSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.data.extension.anime.model.NetworkAnimeExtensionStore
import mihon.domain.extension.anime.model.AnimeExtensionStore
import mihon.domain.extensionrepo.model.NetworkMangaExtensionStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StoreCatalogueTest {
    private val catalogue = AnimeExtensionStore(
        "https://example.invalid/index.json",
        "Test catalogue",
        "TEST",
        "a".repeat(64),
        AnimeExtensionStore.Contact("https://example.invalid", null),
        false,
        null,
    )

    private fun available(home: Int?) = AnimeExtension.Available(
        name = "Test extension", pkgName = "test.extension.media", versionName = "17.2", versionCode = 2,
        libVersion = 17.0, lang = "en", isNsfw = false, isTorrent = false,
        advertisedHomeVersion = home,
        sources = listOf(AnimeExtension.Available.AnimeSource(41, "en", "Test source", "")),
        apkUrl = "https://example.invalid/package.apk", iconUrl = "https://example.invalid/icon.png", store = catalogue,
    )

    @Test fun `available capability is distinct from catalogue branding`() {
        val modern = StoreCatalogue.anime(emptyList(), emptyList(), listOf(available(1))).single()
        assertTrue(modern.ready)
        assertEquals(StoreAction.INSTALL, modern.action)
        assertFalse(StoreCatalogue.anime(emptyList(), emptyList(), listOf(available(null))).single().ready)
        assertFalse(StoreCatalogue.anime(emptyList(), emptyList(), listOf(available(2))).single().ready)
    }

    @Test fun `installed extensions remain visible with an unavailable catalogue`() {
        val installed = AnimeExtension.Installed(
            "Test extension", "test.extension.media", "17.1", 1, 17.0, "en", false, false,
            null, emptyList(), null, isShared = true,
            metadata = ExtensionPackageMetadata(home = ExtensionHomeSupport.READY),
        )
        val entry = StoreCatalogue.anime(listOf(installed), emptyList(), emptyList()).single()
        assertTrue(entry.installed)
        assertTrue(entry.ready)
        assertEquals(StoreAction.OPEN, entry.action)
    }

    @Test fun `different catalogue origins are never silently collapsed`() {
        val first = available(1)
        val second = first.copy(store = catalogue.copy(indexUrl = "https://other.invalid/index.json"))
        val entries = StoreCatalogue.anime(emptyList(), emptyList(), listOf(first, second))
        assertEquals(2, entries.size)
        assertEquals(2, entries.map { it.id }.distinct().size)
    }

    private fun legacyAnime() = AnimeExtension.Installed(
        "Test extension", "test.extension.media", "17.1", 1, 17.0, "en", false, false,
        null,
        listOf(
            mockk<AnimeSource> {
                every { id } returns 41L
                every { name } returns "Test source"
                every { lang } returns "en"
            },
        ),
        null, isShared = true, store = catalogue,
    )

    private fun readyAnime() = available(1).copy(
        store = catalogue.copy(indexUrl = "https://other.invalid/index.json"),
        expectedSigner = "c".repeat(64),
        apkSha256 = "b".repeat(64),
    )

    @Test fun `legacy APKs are not offered a Ready conversion even with a unique alternative`() {
        val ready = readyAnime()
        val entry = StoreCatalogue.anime(listOf(legacyAnime()), emptyList(), listOf(available(null), ready)).single()
        assertEquals(StoreAction.OPEN, entry.action)
        assertEquals(available(null), entry.animeCandidate)
        assertFalse(entry.ready)
    }

    @Test fun `two eligible Ready publishers remain ambiguous and ordinary updates keep their origin`() {
        val ready = readyAnime()
        val third = ready.copy(store = catalogue.copy(indexUrl = "https://third.invalid/index.json"))
        val legacy = available(null)
        assertEquals(
            StoreAction.OPEN,
            StoreCatalogue.anime(listOf(legacyAnime()), emptyList(), listOf(legacy, ready, third)).single().action,
        )
        val update = StoreCatalogue.anime(
            listOf(legacyAnime().copy(hasUpdate = true)),
            emptyList(),
            listOf(legacy, ready),
        ).single()
        assertEquals(StoreAction.UPDATE, update.action)
        assertEquals(legacy, update.animeCandidate)
        assertEquals(
            StoreAction.OPEN,
            StoreCatalogue.anime(
                listOf(legacyAnime().copy(keepVersion = true)),
                emptyList(),
                listOf(ready),
            ).single().action,
        )
    }

    @Test fun `manga APKs retain their configured origin without Ready conversion`() {
        val old = MangaExtension.Installed(
            "Test extension", "test.extension.media", "1.4.1", 1, 1.4, "en", false,
            null,
            listOf(
                mockk<MangaSource> {
                    every { id } returns 41L
                    every { name } returns "Test source"
                    every { lang } returns "en"
                },
            ),
            null, isShared = true, repoUrl = catalogue.indexUrl,
        )
        val legacy = MangaExtension.Available(
            "Test extension", "test.extension.media", "1.4.2", 2, 1.4, "en", false,
            sources = listOf(MangaExtension.Available.MangaSource(41, "en", "Test source", "")),
            apkUrl = "https://example.invalid/package.apk", iconUrl = "https://example.invalid/icon.png",
            repoUrl = catalogue.indexUrl,
        )
        val ready = legacy.copy(
            repoUrl = "https://other.invalid/index.json",
            advertisedHomeVersion = 1,
            expectedSigner = "c".repeat(64),
            apkSha256 = "b".repeat(64),
        )
        val entry = StoreCatalogue.manga(listOf(old), emptyList(), listOf(legacy, ready)).single()
        assertEquals(StoreAction.OPEN, entry.action)
        assertEquals(legacy, entry.mangaCandidate)
        val conflict = ready.copy(repoUrl = "https://third.invalid/index.json")
        assertEquals(
            StoreAction.OPEN,
            StoreCatalogue.manga(listOf(old), emptyList(), listOf(legacy, ready, conflict)).single().action,
        )
        assertEquals(
            legacy,
            StoreCatalogue.manga(listOf(old.copy(hasUpdate = true)), emptyList(), listOf(legacy, ready))
                .single().mangaCandidate,
        )
    }

    @Test fun `search retains declared source names identifiers domains and alternatives`() {
        val candidate = available(1).copy(
            sources = listOf(
                AnimeExtension.Available.AnimeSource(9142L, "en", "Another label", "https://example.invalid"),
            ),
        )
        val entry = StoreCatalogue.anime(emptyList(), emptyList(), listOf(candidate)).single()
        assertTrue(entry.matchesQuery("another LABEL"))
        assertTrue(entry.matchesQuery("9142"))
        assertTrue(entry.matchesQuery("example.invalid"))
        assertTrue(entry.matchesQuery("absent, another label"))
        assertFalse(entry.matchesQuery("absent,  , missing"))
        assertTrue(entry.matchesQuery(" "))
    }

    @Test fun `language choices filter Multiple too but never hide installed packages`() {
        val entry = StoreCatalogue.anime(emptyList(), emptyList(), listOf(available(1))).single()
            .copy(languages = setOf("all"))
        assertFalse(entry.matchesLanguages(setOf("en")))
        assertFalse(entry.matchesLanguages(emptySet()))
        assertTrue(entry.matchesLanguages(setOf("all")))
        assertTrue(entry.copy(installed = true).matchesLanguages(emptySet()))
        assertTrue(entry.copy(languages = emptySet()).matchesLanguages(emptySet()))
    }

    @Test fun `multilingual TV catalogues remain discoverable with either supported language`() {
        val entry = StoreCatalogue.anime(emptyList(), emptyList(), listOf(available(1))).single()
            .copy(medium = StoreMedium.TV, languages = setOf("all"))
        assertTrue(entry.matchesLanguages(setOf("it")))
        assertTrue(entry.matchesLanguages(setOf("en")))
        assertTrue(entry.matchesLanguages(setOf("it", "en")))
        assertFalse(entry.matchesLanguages(emptySet()))
        assertTrue(entry.copy(installed = true).matchesLanguages(emptySet()))
    }

    @Test fun `ordinary TV languages still respect the selected language`() {
        val entry = StoreCatalogue.anime(emptyList(), emptyList(), listOf(available(1))).single()
            .copy(medium = StoreMedium.TV)
        assertTrue(entry.matchesLanguages(setOf("en")))
        assertFalse(entry.matchesLanguages(setOf("it")))
        assertFalse(entry.copy(languages = setOf("fr")).matchesLanguages(setOf("it", "en")))
    }

    @Test fun `an open available card follows package installation and trust instead of its former key`() {
        val selected = StoreCatalogue.anime(emptyList(), emptyList(), listOf(available(1))).single()
        val installed = selected.copy(id = "installed", installed = true, action = StoreAction.TRUST)
        val unrelated = installed.copy(id = "other", packageName = "test.extension.other")
        assertEquals(installed, StoreCatalogue.currentEntry(selected, listOf(unrelated, installed)))
        assertEquals(selected, StoreCatalogue.currentEntry(selected, listOf(selected, unrelated)))
        assertEquals(null, StoreCatalogue.currentEntry(selected, listOf(unrelated)))
    }

    @Test fun `an ordinary upgrade to Ready keeps the existing update path`() {
        val old = AnimeExtension.Installed(
            "Test extension", "test.extension.media", "17.1", 1, 17.0, "en", false, false,
            null, emptyList(), null, isShared = true, store = catalogue, hasUpdate = true,
        )
        val entry = StoreCatalogue.anime(listOf(old), emptyList(), listOf(available(1))).single()
        assertEquals(StoreAction.UPDATE, entry.action)
        assertTrue(entry.ready)
    }

    @Test fun `a Home declaration cannot make an unsupported API Ready`() {
        val entry = StoreCatalogue.anime(emptyList(), emptyList(), listOf(available(1).copy(libVersion = 999.0)))
        assertFalse(entry.single().ready)
    }

    @Test fun `capability and digest are optional in both catalogue formats`() {
        val entry = """{
          "name":"Test extension", "packageName":"test.extension.media",
          "resources":{"apkUrl":"https://example.invalid/package.apk","iconUrl":"https://example.invalid/icon.png"},
          "extensionLib":"17", "versionCode":2, "versionName":"17.2", "contentWarning":"SAFE", "sources":[]
        }"""
        val old = Json.decodeFromString<NetworkAnimeExtensionStore.Extension>(entry)
        assertEquals(null, old.nyanimeHomeVersion)
        val modern = entry.replace(
            "\"sources\":[]",
            "\"sources\":[],\"nyanimeHomeVersion\":1,\"sha256\":\"${"b".repeat(64)}\"",
        )
        val anime = Json.decodeFromString<NetworkAnimeExtensionStore.Extension>(modern)
        val manga = Json.decodeFromString<NetworkMangaExtensionStore.Extension>(modern)
        assertEquals(1, anime.nyanimeHomeVersion)
        assertEquals(anime.nyanimeHomeVersion, manga.nyanimeHomeVersion)
        assertEquals("b".repeat(64), anime.sha256)
        assertEquals(anime.sha256, manga.sha256)
    }
}

package eu.kanade.tachiyomi.extension

import eu.kanade.domain.extension.ExtensionCatalogueLink
import eu.kanade.domain.extension.ExtensionCatalogueMedium
import eu.kanade.domain.extension.UnifiedExtensionCatalogue
import eu.kanade.domain.extension.UnifiedExtensionCatalogue.LinkedCatalogue
import eu.kanade.presentation.more.settings.screen.browse.DriveCatalogueResolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UnifiedExtensionCatalogueTest {
    private val catalogue = """{
      "name":"Example catalogue", "badgeLabel":"Example", "signingKey":"${"a".repeat(64)}",
      "contact":{"website":"https://catalogue.invalid"}, "media":["anime","manga"],
      "extensionList":{"extensions":[
        {"packageName":"example.video", "medium":"anime"},
        {"packageName":"example.reader", "medium":"manga"}]},
      "replaces":["https://catalogue.invalid/previous/index.json"]
    }"""

    @Test fun oneCatalogueSeparatesMediaWithoutSourceNames() {
        val result = UnifiedExtensionCatalogue.parse(catalogue)
        assertEquals(1, result.count("anime"))
        assertEquals(1, result.count("manga"))
        assertEquals(1, result.replaces.size)
        assertTrue(ExtensionCatalogueMedium.accepts(null, "anime"))
        assertTrue(ExtensionCatalogueMedium.accepts(null, "manga"))
        assertFalse(ExtensionCatalogueMedium.accepts("manga", "anime"))
        assertFalse(ExtensionCatalogueMedium.accepts("news", "manga"))
        assertFalse(ExtensionCatalogueMedium.accepts("future", "anime"))
    }

    @Test fun malformedIdentityAndDuplicatePackagesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            UnifiedExtensionCatalogue.parse(catalogue.replace("a".repeat(64), "bad"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            UnifiedExtensionCatalogue.parse(catalogue.replace("example.reader", "example.video"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            UnifiedExtensionCatalogue.parse(catalogue.replace("\"medium\":\"manga\"", "\"medium\":\"unknown\""))
        }
    }

    @Test fun importUrlsMustBeDirectHttpsDocuments() {
        assertTrue(UnifiedExtensionCatalogue.validUrl("https://catalogue.invalid/index.json"))
        listOf(
            "http://catalogue.invalid/index.json",
            "https://user@catalogue.invalid/index.json",
            "https://catalogue.invalid/index.json#fragment",
            "https://catalogue.invalid/index.json?redirect=1",
        ).forEach {
            assertFalse(UnifiedExtensionCatalogue.validUrl(it))
        }
    }

    @Test fun driveAliasesResolveToTheSameCanonicalIndex() {
        val folder = "https://drive.google.com/drive/folders/folder_id?usp=sharing&resourcekey=0-folder-key"
        val folderLink = ExtensionCatalogueLink.parse(folder) as ExtensionCatalogueLink.DriveFolder
        assertEquals("https://drive.google.com/embeddedfolderview?id=folder_id&resourcekey=0-folder-key", folderLink.listingUrl)
        assertFalse(UnifiedExtensionCatalogue.validUrl(folder))

        val canonical = "https://drive.google.com/uc?export=download&id=file_id&resourcekey=0-file-key&filename=index.json"
        val aliases = listOf(
            "https://drive.google.com/file/d/file_id/view?usp=drive_link&resourcekey=0-file-key",
            "https://drive.google.com/uc?id=file_id&resourcekey=0-file-key&export=download",
            "https://drive.usercontent.google.com/download?id=file_id&resourcekey=0-file-key&export=download&confirm=t",
            canonical,
        )
        aliases.forEach {
            assertEquals(ExtensionCatalogueLink.Index(canonical, isDrive = true), ExtensionCatalogueLink.parse(it))
        }
        assertTrue(UnifiedExtensionCatalogue.validUrl(canonical))
    }

    @Test fun driveParsingRejectsAmbiguousParametersAndExternalHosts() {
        listOf(
            "http://drive.google.com/drive/folders/example?usp=sharing",
            "https://drive.google.com.evil.test/drive/folders/example?usp=sharing",
            "https://example@drive.google.com/drive/folders/id?usp=sharing",
            "https://drive.google.com/drive/folders/example?usp=sharing#fragment",
            "https://drive.google.com:444/file/d/example/view",
            "https://drive.google.com/drive/folders/example?resourcekey=foo%26id%3Dother",
            "https://drive.google.com/uc?id=one&id=other",
            "https://drive.google.com/uc?id=one&redirect=other",
            "https://drive.google.com/uc?id=one&filename=outside.json",
        ).forEach {
            assertNull(ExtensionCatalogueLink.parse(it), it)
        }
        assertTrue(ExtensionCatalogueLink.isLegacyRepositoryUrl("https://catalogue.invalid/repo/"))
    }

    @Test fun driveFolderMustContainOnePublicIndex() {
        fun entry(href: String, title: String = "index.json") =
            """<div class="flip-entry"><a href="$href"><div class="flip-entry-title">$title</div></a></div>"""

        val valid = entry("/file/d/file_id/view?usp=sharing&amp;resourcekey=0-file-key")
        val otherFile = entry("/file/d/other_id/view", "bridge.apk")
        val fake = entry("https://third-party.invalid/index.json")
        val expected = "https://drive.google.com/uc?export=download&id=file_id&resourcekey=0-file-key&filename=index.json"

        assertEquals(expected, DriveCatalogueResolver.findIndex(valid + otherFile + fake + valid))
        assertNull(DriveCatalogueResolver.findIndex(fake + otherFile))
        assertNull(DriveCatalogueResolver.findIndex(valid + entry("/file/d/second_file/view")))
    }

    @Test fun bridgesCountOnlyApkEntries() {
        val packageList = """"packageList":{"extensions":[
           {"format":"apk", "manifest":{"medium":"layer"}},
           {"format":"apk", "manifest":{"medium":"manga-layer"}},
           {"format":"apk", "manifest":{"medium":"anime"}},
           {"format":"old", "manifest":{"medium":"layer"}},
           {"manifest":{"medium":"manga-layer"}}
        ]}"""
        val result = UnifiedExtensionCatalogue.parse(catalogue.trimEnd().dropLast(1) + ",$packageList}")
        assertEquals(2, result.bridgeCount)
    }

    @Test fun readsExplicitAnimeAndMangaCatalogueLinks() {
        val anime = "https://anime.invalid/index.pb"
        val manga = "https://manga.invalid/index.json"
        val result = withCatalogueLinks("""
            {"url":"$anime","type":"anime","signingKey":"${"b".repeat(64)}"},
            {"url":"$manga","type":"manga","signingKey":"${"c".repeat(64)}"}
        """.trimIndent())
        assertEquals(
            listOf(
                LinkedCatalogue(anime, LinkedCatalogue.Type.ANIME, "b".repeat(64)),
                LinkedCatalogue(manga, LinkedCatalogue.Type.MANGA, "c".repeat(64)),
            ),
            result.catalogues,
        )
        assertTrue(UnifiedExtensionCatalogue.parse(catalogue).catalogues.isEmpty())
    }

    @Test fun rejectsDuplicateOrUnverifiableCatalogueLinks() {
        val signer = "b".repeat(64)
        fun link(url: String, type: String = "manga", key: String = signer) =
            """{"url":"$url","type":"$type","signingKey":"$key"}"""

        listOf(
            link("http://catalogue.invalid/index.pb"),
            link("https://user@catalogue.invalid/index.pb"),
            link("https://catalogue.invalid/index.pb?index=other.pb"),
            link("https://catalogue.invalid/index.pb#other.pb"),
            link("https://catalogue.invalid/repo"),
            link("https://drive.google.com/drive/folders/folder_id?usp=sharing"),
            link("https://catalogue.invalid/index.pb", "tv"),
            link("https://catalogue.invalid/index.pb", key = "unverified"),
            link("https://catalogue.invalid/index.pb") + "," +
                link("https://CATALOGUE.invalid:443/index.pb", "anime"),
            link("https://catalogue.invalid/index.pb") + "," + link("https://other.invalid/index.pb"),
        ).forEach { links ->
            assertThrows(IllegalArgumentException::class.java) { withCatalogueLinks(links) }
        }
    }

    private fun withCatalogueLinks(links: String): UnifiedExtensionCatalogue =
        UnifiedExtensionCatalogue.parse(catalogue.trimEnd().dropLast(1) + ",\"catalogues\":[$links]}")
}

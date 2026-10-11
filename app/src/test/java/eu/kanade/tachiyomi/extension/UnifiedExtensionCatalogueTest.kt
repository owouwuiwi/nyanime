package eu.kanade.tachiyomi.extension

import eu.kanade.domain.extension.ExtensionCatalogueMedium
import eu.kanade.domain.extension.UnifiedExtensionCatalogue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
}

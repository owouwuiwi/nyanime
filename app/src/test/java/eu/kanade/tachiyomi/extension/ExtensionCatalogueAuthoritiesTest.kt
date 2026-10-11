package eu.kanade.tachiyomi.extension

import eu.kanade.domain.extension.ExtensionCatalogueAuthorities
import eu.kanade.domain.extension.UnifiedExtensionCatalogue
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.core.common.preference.PreferenceStore

class ExtensionCatalogueAuthoritiesTest {
    private val url = "https://catalogue.invalid/index.json"
    private val key = "a".repeat(64)
    private val catalogue = UnifiedExtensionCatalogue(
        "Example catalogue",
        "Example",
        key,
        UnifiedExtensionCatalogue.Contact("https://catalogue.invalid"),
        setOf("anime", "manga", "news"),
        UnifiedExtensionCatalogue.ExtensionList(emptyList()),
        authority = UnifiedExtensionCatalogue.Authority(recommendReplacements = true),
    )

    private fun preferences(): PreferenceStore {
        val values = mutableMapOf<String, InMemoryPreference<String>>()
        return mockk {
            every { getString(any(), any()) } answers {
                values.getOrPut(firstArg()) { InMemoryPreference(firstArg(), null, secondArg()) }
            }
        }
    }

    @Test fun `recommendations require explicit import approval and remain bound to URL signer and medium`() {
        val store = preferences()
        val authorities = ExtensionCatalogueAuthorities(store)
        assertTrue(authorities.get().isEmpty())
        authorities.approve(url, catalogue)
        val approved = ExtensionCatalogueAuthorities(store).get().single()
        assertTrue(approved.permits(url, key, "anime"))
        assertTrue(approved.permits(url, key.uppercase(), "news"))
        assertFalse(approved.permits("https://other.invalid/index.json", key, "anime"))
        assertFalse(approved.permits(url, "b".repeat(64), "anime"))
        assertFalse(approved.permits(url, key, "unknown"))
        authorities.remove(url)
        assertTrue(authorities.get().isEmpty())
    }

    @Test fun `reimporting without authority removes approval and a moved catalogue cannot leave an old authority`() {
        val authorities = ExtensionCatalogueAuthorities(preferences())
        authorities.approve(url, catalogue)
        val moved = "https://catalogue.invalid/moved/index.json"
        authorities.approve(moved, catalogue.copy(replaces = setOf(url)))
        assertEquals(listOf(moved), authorities.get().map { it.url })
        authorities.approve(moved, catalogue.copy(authority = null))
        assertTrue(authorities.get().isEmpty())
    }

    @Test fun `unknown authority versions are rejected rather than granting new permissions`() {
        val document = """{"name":"Example","badgeLabel":"Example","signingKey":"$key",
          "contact":{"website":"https://catalogue.invalid"},"media":["anime"],
          "extensionList":{"extensions":[]},"authority":{"version":2,"recommendReplacements":true}}"""
        assertTrue(runCatching { UnifiedExtensionCatalogue.parse(document) }.isFailure)
        assertTrue(
            UnifiedExtensionCatalogue.parse(
                document.replace("\"version\":2", "\"version\":1"),
            ).authority!!.recommendReplacements,
        )
    }
}

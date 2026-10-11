package eu.kanade.tachiyomi.extension

import eu.kanade.domain.extension.ExtensionCatalogueTrust
import eu.kanade.domain.extension.UnifiedExtensionCatalogue
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.core.common.preference.PreferenceStore

class ExtensionCatalogueTrustTest {
    private fun preferences(): PreferenceStore {
        val values = mutableMapOf<String, InMemoryPreference<String>>()
        return mockk {
            every { getString(any(), any()) } answers {
                values.getOrPut(firstArg()) { InMemoryPreference(firstArg(), null, secondArg()) }
            }
        }
    }
    private val url = "https://catalogue.invalid/index.json"
    private val signer = "a".repeat(64)

    @Test fun `execution consent starts disabled and stays bound to catalogue identity`() {
        val store = preferences()
        val trust = ExtensionCatalogueTrust(store)
        assertFalse(trust.permits(url, signer))
        trust.set(url, signer, true)
        assertTrue(ExtensionCatalogueTrust(store).permits(url, signer.uppercase()))
        assertFalse(trust.permits(url, "b".repeat(64)))
        assertFalse(trust.permits("https://other.invalid/index.json", signer))
        trust.remove(url)
        assertFalse(trust.permits(url, signer))
    }

    @Test fun `obsolete authority metadata grants no execution consent`() {
        val document = """{"name":"Example","badgeLabel":"Example","signingKey":"$signer",
          "contact":{"website":"https://catalogue.invalid"},"media":["anime"],
          "extensionList":{"extensions":[]},"authority":{"version":2,"recommendReplacements":true}}"""
        UnifiedExtensionCatalogue.parse(document)
        assertTrue(ExtensionCatalogueTrust(preferences()).get().isEmpty())
    }

    @Test fun `consent can be revoked without changing catalogue configuration`() {
        val trust = ExtensionCatalogueTrust(preferences())
        trust.set(url, signer, true)
        trust.set(url, signer, false)
        assertTrue(trust.get().isEmpty())
    }
}

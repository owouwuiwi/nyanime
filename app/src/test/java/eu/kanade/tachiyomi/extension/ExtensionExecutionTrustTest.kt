package eu.kanade.tachiyomi.extension

import eu.kanade.domain.extension.ExtensionCatalogueTrust
import eu.kanade.domain.extension.ExtensionExecutionTrust
import eu.kanade.domain.extension.ExtensionPackageMetadata
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.core.common.preference.PreferenceStore

class ExtensionExecutionTrustTest {
    private fun preferences(): PreferenceStore {
        val strings = mutableMapOf<String, InMemoryPreference<String>>()
        val booleans = mutableMapOf<String, InMemoryPreference<Boolean>>()
        return mockk {
            every { getString(any(), any()) } answers {
                strings.getOrPut(firstArg()) { InMemoryPreference(firstArg(), null, secondArg()) }
            }
            every { getBoolean(any(), any()) } answers {
                booleans.getOrPut(firstArg()) { InMemoryPreference(firstArg(), null, secondArg()) }
            }
        }
    }
    private val name = "example.extension"
    private val publisher = ExtensionPackageMetadata(signers = setOf("a".repeat(64)))
    private val url = "https://catalogue.invalid/index.json"
    private val key = "c".repeat(64)

    @Test fun `ordinary updates keep individual consent and unrelated publishers do not`() {
        val trust = ExtensionExecutionTrust(preferences())
        assertFalse(trust.permits(name, publisher))
        trust.approve(name, publisher)
        assertTrue(trust.permits(name, publisher))
        assertFalse(trust.permits("other.extension", publisher))
        assertFalse(trust.permits(name, publisher.copy(signers = setOf("b".repeat(64)))))
    }

    @Test fun `revoking the catalogue invalidates delegated consent without a legacy bypass`() {
        val store = preferences()
        val catalogues = ExtensionCatalogueTrust(store)
        val trust = ExtensionExecutionTrust(store)
        catalogues.set(url, key, true)
        trust.approve(name, publisher, url, key)
        assertTrue(trust.permits(name, publisher))
        catalogues.remove(url)
        assertFalse(trust.permits(name, publisher))
        assertFalse(trust.allowsLegacy(name))
        assertThrows(IllegalArgumentException::class.java) { trust.approve(name, publisher, url, key) }
        trust.approve(name, publisher)
        assertTrue(trust.permits(name, publisher))
    }

    @Test fun `individual approval never becomes dependent on a later catalogue approval`() {
        val store = preferences()
        val trust = ExtensionExecutionTrust(store)
        trust.approve(name, publisher)
        val catalogues = ExtensionCatalogueTrust(store)
        catalogues.set(url, key, true)
        trust.approve(name, publisher, url, key)
        catalogues.remove(url)
        assertTrue(trust.permits(name, publisher))
    }

    @Test fun `verified single signer rotation preserves approval but multiple signers require exact consent`() {
        val trust = ExtensionExecutionTrust(preferences())
        trust.approve(name, publisher)
        val rotated = publisher.copy(
            signers = setOf("b".repeat(64)),
            signerHistory = publisher.signers + "b".repeat(64),
        )
        assertTrue(trust.permits(name, rotated))
        assertFalse(trust.permits(name, rotated.copy(signers = publisher.signers + "b".repeat(64))))
    }

    @Test fun `revoke all prevents old family settings from silently reapproving packages`() {
        val trust = ExtensionExecutionTrust(preferences())
        assertTrue(trust.allowsLegacy(name))
        trust.approve(name, publisher)
        trust.revokeAll()
        assertFalse(trust.permits(name, publisher))
        assertFalse(trust.allowsLegacy(name))
        trust.approve(name, publisher)
        assertTrue(trust.permits(name, publisher))
    }
}

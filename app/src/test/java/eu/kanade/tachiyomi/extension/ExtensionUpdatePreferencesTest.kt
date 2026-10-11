package eu.kanade.tachiyomi.extension

import eu.kanade.domain.extension.ExtensionPackageMetadata
import eu.kanade.tachiyomi.data.backup.BackupPreferencePolicy
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.core.common.preference.PreferenceStore

class ExtensionUpdatePreferencesTest {
    private class Store {
        val sets = mutableMapOf<String, InMemoryPreference<Set<String>>>()
        val preferences = mockk<PreferenceStore> {
            every { getStringSet(any(), any()) } answers {
                val key = firstArg<String>()
                sets.getOrPut(key) { InMemoryPreference(key, null, secondArg()) }
            }
            every { getAll() } answers { sets.mapValues { it.value.get() } }
        }
    }
    private val signer = ExtensionPackageMetadata(signers = setOf("a".repeat(64)))

    @Test fun settingsSurviveRecreationAndAreScopedToPackageAndSigner() {
        val store = Store().preferences
        val policies = ExtensionUpdatePreferences(store, "anime")
        policies.bind("example.extension", signer, "https://catalogue.invalid/index.json")
        val restored = ExtensionUpdatePreferences(store, "anime")
        assertFalse(restored.keep("example.extension", signer))
        assertEquals("https://catalogue.invalid/index.json", restored.repository("example.extension", signer))
        assertFalse(restored.keep("example.extension", signer.copy(signers = setOf("b".repeat(64)))))
        assertNull(restored.repository("example.extension", signer.copy(signers = setOf("b".repeat(64)))))
        assertFalse(ExtensionUpdatePreferences(store, "manga").keep("example.extension", signer))
        assertTrue(store.getAll().keys.all(BackupPreferencePolicy::isPortable))
    }

    @Test fun malformedRecordsAndUnsignedPackagesCannotCreateABinding() {
        val store = Store().preferences
        store.getStringSet("nyanime_extension_policies_manga").set(setOf("not json"))
        val policies = ExtensionUpdatePreferences(store, "manga")
        assertFalse(policies.keep("example.extension", signer))
        policies.bind("example.extension", ExtensionPackageMetadata(), "https://catalogue.invalid")
        assertNull(policies.repository("example.extension", ExtensionPackageMetadata()))
    }

    @Test fun retiredKeepFlagsAreClearedWithoutLosingSignerBoundRepository() {
        val store = Store().preferences
        val key = "nyanime_extension_policies_anime"
        store.getStringSet(key).set(
            setOf(
                """{"packageName":"example.extension","signers":["${"a".repeat(64)}"],
                "keep":true,"repository":"https://catalogue.invalid/index.json"}""",
            ),
        )
        val policies = ExtensionUpdatePreferences(store, "anime")
        assertFalse(policies.keep("example.extension", signer))
        assertEquals("https://catalogue.invalid/index.json", policies.repository("example.extension", signer))
        assertTrue(store.getStringSet(key).get().all { "\"keep\":false" in it })
    }
}

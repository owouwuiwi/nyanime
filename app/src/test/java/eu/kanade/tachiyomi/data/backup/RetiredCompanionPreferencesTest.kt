package eu.kanade.tachiyomi.data.backup

import android.content.Context
import eu.kanade.tachiyomi.data.backup.create.creators.PreferenceBackupCreator
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class RetiredCompanionPreferencesTest {
    private val retired = mapOf(
        "companion_enabled" to true,
        "companion_frequency" to "present",
        "companion_character" to "stellar",
        "companion_tastes" to setOf("entry|1|123"),
        "companion_dismissed" to setOf("entry|456"),
    )

    @Test fun cleanupRemovesMascotValuesWithoutTouchingUnrelatedSettingsOrReadingTheirTypes() {
        val store = mockk<PreferenceStore>()
        every { store.getAll() } returns
            retired + mapOf("pref_theme" to "Dark", "companion_receiver_address" to "local")
        val handle = mockk<Preference<String>>(relaxed = true)
        every { store.getString(any(), any()) } returns handle

        BackupPreferencePolicy.removeRetired(store)

        retired.keys.forEach { key ->
            assertTrue(BackupPreferencePolicy.isRetired(key))
            verify(exactly = 1) { store.getString(key, any()) }
        }
        verify(exactly = retired.size) { handle.delete() }
        verify(exactly = 0) { handle.get() }
        verify(exactly = 0) { store.getString("pref_theme", any()) }
        verify(exactly = 0) { store.getString("companion_receiver_address", any()) }
        assertFalse(BackupPreferencePolicy.isRetired("companion_receiver_address"))
    }

    @Test fun newBackupsExcludeRetiredSettingsEvenBeforeStartupCleanup() {
        val store = mockk<PreferenceStore>()
        every { store.getAll() } returns retired + mapOf("pref_theme" to "Dark")
        val creator = PreferenceBackupCreator(
            animeSourceManager = null,
            mangaSourceManager = null,
            preferenceStore = store,
        )

        assertEquals(listOf(BackupPreference("pref_theme", StringPreferenceValue("Dark"))), creator.createApp(true))
    }

    @Test fun anOlderBackupRestoresNormalPreferencesWithoutRevivingTheMascot() = runBlocking {
        val store = mockk<PreferenceStore>()
        val theme = mockk<Preference<String>>(relaxed = true)
        every { store.getString("pref_theme", any()) } returns theme
        val restorer = PreferenceRestorer(
            context = mockk<Context>(),
            getMangaCategories = mockk(),
            getAnimeCategories = mockk(),
            preferenceStore = store,
            reschedule = false,
        )
        val oldPreferences = retired.map { (key, value) ->
            BackupPreference(
                key,
                when (value) {
                    is Boolean -> BooleanPreferenceValue(value)
                    is Set<*> -> StringSetPreferenceValue(value.filterIsInstance<String>().toSet())
                    else -> StringPreferenceValue(value.toString())
                },
            )
        } +
            BackupPreference("pref_theme", StringPreferenceValue("Dark"))

        assertTrue(restorer.restoreApp(oldPreferences, null, null).isEmpty())
        verify(exactly = 1) { theme.set("Dark") }
        retired.keys.forEach { key -> verify(exactly = 0) { store.getString(key, any()) } }
        verify(exactly = 0) { store.getBoolean(any(), any()) }
        verify(exactly = 0) { store.getStringSet(any(), any()) }
    }
}

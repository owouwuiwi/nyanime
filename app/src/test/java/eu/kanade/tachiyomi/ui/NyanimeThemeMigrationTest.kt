package eu.kanade.tachiyomi.ui

import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.isDynamicColorAvailable
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.core.common.preference.PreferenceStore

class NyanimeThemeMigrationTest {
    @Test fun modernUiIsAlwaysActiveAfterMigrationWhileOldThemeChoicesRemainStored() {
        val store = store()
        val preferences = UiPreferences(store)
        preferences.appTheme().set(AppTheme.DOOM)
        preferences.installNyanimeThemeOnce()
        assertTrue(preferences.modernUi().get())
        assertEquals(AppTheme.NYANIME, preferences.activeAppTheme())
        preferences.modernUi().set(false)
        assertEquals(AppTheme.NYANIME, preferences.activeAppTheme())
        preferences.legacyAppTheme().set(AppTheme.LAVENDER)
        val recreated = UiPreferences(store)
        recreated.installNyanimeThemeOnce()
        assertFalse(recreated.modernUi().get())
        assertEquals(AppTheme.NYANIME, recreated.activeAppTheme())
        assertEquals(AppTheme.DOOM, recreated.legacyMangaTheme().get())
        recreated.modernUi().set(true)
        assertEquals(AppTheme.NYANIME, recreated.activeAppTheme())
        assertEquals(AppTheme.LAVENDER, recreated.legacyAppTheme().get())
    }

    @BeforeEach
    fun configureDevice() {
        mockkStatic("eu.kanade.tachiyomi.util.system.DeviceUtilExtensionsKt")
        every { DeviceUtil.isDynamicColorAvailable } returns false
    }

    @AfterEach
    fun restoreDevice() {
        unmockkStatic("eu.kanade.tachiyomi.util.system.DeviceUtilExtensionsKt")
    }

    @Test
    fun upgradeKeepsTheExistingMangaThemeAndDisplayPreferences() {
        val preferences = UiPreferences(store())
        preferences.appTheme().set(AppTheme.DOOM)
        preferences.themeMode().set(ThemeMode.LIGHT)
        preferences.themeDarkAmoled().set(true)

        preferences.installNyanimeThemeOnce()

        assertEquals(AppTheme.NYANIME, preferences.appTheme().get())
        assertEquals(AppTheme.DOOM, preferences.legacyMangaTheme().get())
        assertEquals(ThemeMode.LIGHT, preferences.themeMode().get())
        assertTrue(preferences.themeDarkAmoled().get())
    }

    @Test
    fun recreatingTheApplicationDoesNotResetSubsequentThemeChoices() {
        val store = store()
        val preferences = UiPreferences(store)
        preferences.installNyanimeThemeOnce()
        preferences.appTheme().set(AppTheme.LAVENDER)
        preferences.legacyMangaTheme().set(AppTheme.NORD)

        val recreated = UiPreferences(store)
        recreated.installNyanimeThemeOnce()

        assertEquals(AppTheme.LAVENDER, recreated.appTheme().get())
        assertEquals(AppTheme.NORD, recreated.legacyMangaTheme().get())
    }

    @Test
    fun anUnsetThemeKeepsTheOriginalDeviceDefaultForManga() {
        for ((dynamic, expected) in listOf(false to AppTheme.DEFAULT, true to AppTheme.MONET)) {
            every { DeviceUtil.isDynamicColorAvailable } returns dynamic
            val preferences = UiPreferences(store())
            preferences.installNyanimeThemeOnce()
            assertEquals(expected, preferences.legacyMangaTheme().get())
            assertEquals(AppTheme.NYANIME, preferences.appTheme().get())
        }
    }

    private fun store(): PreferenceStore {
        val objects = mutableMapOf<String, InMemoryPreference<Any>>()
        val booleans = mutableMapOf<String, InMemoryPreference<Boolean>>()
        return mockk {
            every { getObject<Any>(any(), any(), any(), any()) } answers {
                val key = firstArg<String>()
                objects.getOrPut(key) { InMemoryPreference(key, null, secondArg()) }
            }
            every { getBoolean(any(), any()) } answers {
                val key = firstArg<String>()
                booleans.getOrPut(key) { InMemoryPreference(key, null, secondArg()) }
            }
        }
    }
}

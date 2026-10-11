package eu.kanade.tachiyomi.ui

import android.app.UiModeManager
import eu.kanade.domain.ui.ThemeController
import eu.kanade.domain.ui.ThemeSettings
import eu.kanade.domain.ui.ThemeSettingsRepository
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.tachiyomi.data.theme.AndroidThemeModeApplier
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AndroidThemeModeApplierTest {
    @Test
    fun sharedModeObserversAreNotifiedAfterApplyingButNotOnForegroundReconciliation() {
        val events = mutableListOf<String>()
        val applier = AndroidThemeModeApplier(
            uiModeManager = null,
            updateLauncherIcon = { events += "icon" },
            sdkInt = 30,
            applyCompatMode = { events += "theme" },
            notifyModeChanged = { events += "notify" },
        )

        applier.apply(ThemeMode.LIGHT)
        applier.reconcile(ThemeMode.LIGHT)

        assertEquals(listOf("theme", "icon", "notify", "icon"), events)
    }

    @Test
    fun android12RegistersExplicitChoicesWithoutACompetingCompatOverride() {
        val system = mockk<UiModeManager>(relaxed = true)
        val icons = mutableListOf<ThemeMode>()
        val compat = mutableListOf<ThemeMode>()
        val applier = AndroidThemeModeApplier(system, icons::add, 31, compat::add)

        applier.apply(ThemeMode.LIGHT)
        applier.apply(ThemeMode.DARK)

        verify(exactly = 1) { system.setApplicationNightMode(UiModeManager.MODE_NIGHT_NO) }
        verify(exactly = 1) { system.setApplicationNightMode(UiModeManager.MODE_NIGHT_YES) }
        verify(exactly = 0) { system.setNightMode(any()) }
        assertTrue(compat.isEmpty())
        assertEquals(listOf(ThemeMode.LIGHT, ThemeMode.DARK), icons)
    }

    @Test
    fun followingSystemClearsThePersistedAppOverrideAfterAnExplicitChoice() {
        val system = mockk<UiModeManager>(relaxed = true)
        val icons = mutableListOf<ThemeMode>()
        val compat = mutableListOf<ThemeMode>()
        val applier = AndroidThemeModeApplier(system, icons::add, 36, compat::add)

        applier.apply(ThemeMode.DARK)
        applier.apply(ThemeMode.SYSTEM)

        verify(exactly = 1) { system.setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO) }
        assertTrue(compat.isEmpty())
        assertEquals(listOf(ThemeMode.DARK, ThemeMode.SYSTEM), icons)
    }

    @Test
    fun android11KeepsCompatForAllModesWithoutCallingTheNewApi() {
        val system = mockk<UiModeManager>(relaxed = true)
        val icons = mutableListOf<ThemeMode>()
        val compat = mutableListOf<ThemeMode>()
        val applier = AndroidThemeModeApplier(system, icons::add, 30, compat::add)

        ThemeMode.entries.forEach(applier::apply)

        verify(exactly = 0) { system.setApplicationNightMode(any()) }
        verify(exactly = 0) { system.setNightMode(any()) }
        assertEquals(ThemeMode.entries, compat)
        assertEquals(ThemeMode.entries, icons)
    }

    @Test
    fun aMissingSystemServiceStillAppliesTheAppearanceAndIcon() {
        val icons = mutableListOf<ThemeMode>()
        val compat = mutableListOf<ThemeMode>()
        val applier = AndroidThemeModeApplier(null, icons::add, 31, compat::add)

        applier.apply(ThemeMode.LIGHT)

        assertEquals(listOf(ThemeMode.LIGHT), compat)
        assertEquals(listOf(ThemeMode.LIGHT), icons)
    }

    @Test
    fun aRejectedNativeUpdateDoesNotCrashConfirmationOrSkipTheIcon() {
        val system = mockk<UiModeManager>(relaxed = true)
        every { system.setApplicationNightMode(any()) } throws SecurityException("Rejected by system")
        val icons = mutableListOf<ThemeMode>()
        val compat = mutableListOf<ThemeMode>()
        val applier = AndroidThemeModeApplier(system, icons::add, 31, compat::add)

        applier.apply(ThemeMode.LIGHT)

        assertEquals(listOf(ThemeMode.LIGHT), compat)
        assertEquals(listOf(ThemeMode.LIGHT), icons)
    }

    @Test
    fun recoveringTheNativeServiceClearsAnEarlierCompatFallback() {
        val system = mockk<UiModeManager>(relaxed = true)
        every { system.setApplicationNightMode(UiModeManager.MODE_NIGHT_NO) } throws SecurityException("Unavailable")
        val compat = mutableListOf<ThemeMode>()
        val applier = AndroidThemeModeApplier(system, {}, 31, compat::add)

        applier.apply(ThemeMode.LIGHT)
        applier.apply(ThemeMode.DARK)
        applier.apply(ThemeMode.SYSTEM)

        assertEquals(listOf(ThemeMode.LIGHT, ThemeMode.SYSTEM), compat)
        verify(exactly = 1) { system.setApplicationNightMode(UiModeManager.MODE_NIGHT_YES) }
        verify(exactly = 1) { system.setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO) }
    }

    @Test
    fun reconcilingOnResumeOrConfigurationChangeDoesNotReapplyTheNativeTheme() {
        val system = mockk<UiModeManager>(relaxed = true)
        val icons = mutableListOf<ThemeMode>()
        val compat = mutableListOf<ThemeMode>()
        val applier = AndroidThemeModeApplier(system, icons::add, 31, compat::add)

        repeat(3) { applier.reconcile(ThemeMode.SYSTEM) }

        verify(exactly = 0) { system.setApplicationNightMode(any()) }
        assertTrue(compat.isEmpty())
        assertEquals(List(3) { ThemeMode.SYSTEM }, icons)
    }

    @Test
    fun startupAlignsAnExistingConfirmedChoiceWithoutSavingOrRepeatingTheChooser() {
        val repository = mockk<ThemeSettingsRepository>()
        every { repository.current() } returns ThemeSettings(ThemeMode.LIGHT, initialChoiceComplete = true)
        val system = mockk<UiModeManager>(relaxed = true)
        val applier = AndroidThemeModeApplier(system, {}, 31, applyCompatMode = { error("Unexpected compat override") })

        ThemeController(repository, applier).restore()

        verify(exactly = 1) { system.setApplicationNightMode(UiModeManager.MODE_NIGHT_NO) }
        verify(exactly = 1) { repository.current() }
        coVerify(exactly = 0) { repository.save(any(), any()) }
        assertTrue(repository.current().initialChoiceComplete)
    }
}

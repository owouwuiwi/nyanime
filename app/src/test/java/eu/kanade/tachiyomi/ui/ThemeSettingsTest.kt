package eu.kanade.tachiyomi.ui

import android.content.SharedPreferences
import eu.kanade.domain.ui.ThemeController
import eu.kanade.domain.ui.ThemeModeApplier
import eu.kanade.domain.ui.ThemePreferenceKeys
import eu.kanade.domain.ui.ThemeSettings
import eu.kanade.domain.ui.ThemeSettingsRepository
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.domain.ui.resolveDarkTheme
import eu.kanade.tachiyomi.data.backup.BackupPreferencePolicy
import eu.kanade.tachiyomi.data.theme.AndroidThemeSettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThemeSettingsTest {
    @Test
    fun explicitChoicesOverrideBothSystemModes() {
        for (systemDark in listOf(false, true)) {
            assertFalse(resolveDarkTheme(ThemeMode.LIGHT, systemDark))
            assertTrue(resolveDarkTheme(ThemeMode.DARK, systemDark))
            assertEquals(systemDark, resolveDarkTheme(ThemeMode.SYSTEM, systemDark))
        }
    }

    @Test
    fun existingModeStillRequiresInitialConfirmation() {
        val store = TestPreferences(mutableMapOf(ThemePreferenceKeys.MODE to ThemeMode.DARK.name))
        assertEquals(ThemeMode.DARK, store.repository.current().mode)
        assertFalse(store.repository.current().initialChoiceComplete)
        assertEquals(ThemeMode.SYSTEM, TestPreferences().repository.current().mode)
    }

    @Test
    fun confirmationPersistsBothValuesInOneCommitAndSurvivesRecreation() = runBlocking {
        val store = TestPreferences()
        assertTrue(store.repository.save(ThemeMode.LIGHT, true).isSuccess)
        assertEquals(setOf(ThemePreferenceKeys.MODE, ThemePreferenceKeys.INITIAL_CHOICE), store.commits.single())
        val recreated = AndroidThemeSettingsRepository(store.preferences)
        assertEquals(ThemeMode.LIGHT, recreated.current().mode)
        assertTrue(recreated.current().initialChoiceComplete)
        assertTrue(recreated.save(ThemeMode.SYSTEM).isSuccess)
        assertTrue(recreated.current().initialChoiceComplete)
    }

    @Test
    fun failedDiskCommitRestoresInMemoryStateAndNeverAppliesTheTheme() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val store = TestPreferences(mutableMapOf(ThemePreferenceKeys.MODE to ThemeMode.DARK.name))
            store.onCommit = { assertFalse(store.repository.settings.value.initialChoiceComplete) }
            store.failNextCommit = true
            var applied = false
            val applier = object : ThemeModeApplier {
                override fun apply(mode: ThemeMode) {
                    applied = true
                }
                override fun reconcile(mode: ThemeMode) = Unit
            }
            val result = ThemeController(store.repository, applier).select(ThemeMode.LIGHT, true)
            assertTrue(result.isFailure)
            assertFalse(applied)
            assertFalse(store.repository.current().initialChoiceComplete)
            assertEquals(ThemeMode.DARK, store.repository.current().mode)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun recreatingTheScreenDuringConfirmationCannotLeaveNativeAppearanceBehind() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val started = CompletableDeferred<Unit>()
            val finishCommit = CompletableDeferred<Unit>()
            val state = MutableStateFlow(ThemeSettings(ThemeMode.SYSTEM, false))
            val repository = object : ThemeSettingsRepository {
                override val settings = state
                override fun current() = state.value
                override suspend fun save(mode: ThemeMode, completeInitialChoice: Boolean): Result<Unit> {
                    started.complete(Unit)
                    finishCommit.await()
                    state.value = ThemeSettings(mode, completeInitialChoice)
                    return Result.success(Unit)
                }
            }
            val applied = mutableListOf<ThemeMode>()
            val applier = object : ThemeModeApplier {
                override fun apply(mode: ThemeMode) {
                    assertTrue(repository.current().initialChoiceComplete)
                    applied.add(mode)
                }
                override fun reconcile(mode: ThemeMode) = Unit
            }
            val screenJob = launch { ThemeController(repository, applier).select(ThemeMode.LIGHT, true) }
            started.await()
            screenJob.cancel()
            finishCommit.complete(Unit)
            screenJob.join()
            assertEquals(ThemeSettings(ThemeMode.LIGHT, true), repository.current())
            assertEquals(listOf(ThemeMode.LIGHT), applied)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun initialChoiceIsDeviceLocalAndAnUnknownStoredModeFallsBackToSystem() {
        assertFalse(BackupPreferencePolicy.isPortable(ThemePreferenceKeys.INITIAL_CHOICE))
        assertTrue(BackupPreferencePolicy.isPortable(ThemePreferenceKeys.MODE))
        val store = TestPreferences(mutableMapOf(ThemePreferenceKeys.MODE to "future-theme"))
        assertEquals(ThemeMode.SYSTEM, store.repository.current().mode)
    }

    private class TestPreferences(private val values: MutableMap<String, Any?> = mutableMapOf()) {
        val commits = mutableListOf<Set<String>>()
        private val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()
        var onCommit: () -> Unit = {}
        var failNextCommit = false
        val preferences = mockk<SharedPreferences>()
        val repository by lazy { AndroidThemeSettingsRepository(preferences) }

        init {
            every { preferences.all } answers { values.toMap() }
            every { preferences.registerOnSharedPreferenceChangeListener(any()) } answers { listeners.add(firstArg()) }
            every { preferences.getString(any(), any()) } answers { values[firstArg()] as? String ?: secondArg() }
            every { preferences.getBoolean(any(), any()) } answers { values[firstArg()] as? Boolean ?: secondArg() }
            every { preferences.edit() } answers {
                val changes = mutableMapOf<String, Any?>()
                val editor = mockk<SharedPreferences.Editor>()
                every { editor.putString(any(), any()) } answers {
                    changes[firstArg()] = secondArg<String?>()
                    editor
                }
                every { editor.putBoolean(any(), any()) } answers {
                    changes[firstArg()] = secondArg<Boolean>()
                    editor
                }
                every { editor.commit() } answers {
                    commits.add(changes.keys.toSet())
                    values.putAll(changes)
                    changes.keys.forEach { key -> listeners.forEach { it.onSharedPreferenceChanged(preferences, key) } }
                    onCommit()
                    val success = !failNextCommit
                    failNextCommit = false
                    success
                }
                editor
            }
        }
    }
}

package eu.kanade.domain.ui

import eu.kanade.domain.ui.model.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tachiyomi.core.common.preference.Preference

internal object ThemePreferenceKeys {
    const val MODE = "pref_theme_mode_key"
    val INITIAL_CHOICE = Preference.appStateKey("nyanime_initial_theme_choice_complete")
}

data class ThemeSettings(val mode: ThemeMode, val initialChoiceComplete: Boolean)

interface ThemeSettingsRepository {
    val settings: Flow<ThemeSettings>
    fun current(): ThemeSettings
    suspend fun save(mode: ThemeMode, completeInitialChoice: Boolean = false): Result<Unit>
}

/** Independent of Android configuration, so explicit choices also work in previews. */
fun resolveDarkTheme(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> systemDark
}

interface ThemeModeApplier {
    fun apply(mode: ThemeMode)
    fun reconcile(mode: ThemeMode)
}

class ThemeController(
    private val repository: ThemeSettingsRepository,
    private val applier: ThemeModeApplier,
) {
    private val changes = Mutex()
    fun restore() = applier.apply(repository.current().mode)
    fun reconcile() = applier.reconcile(repository.current().mode)

    suspend fun select(mode: ThemeMode, completeInitialChoice: Boolean = false): Result<Unit> = changes.withLock {
        // Once the disk transaction begins, a recreated screen must not cancel its native effects.
        withContext(NonCancellable) {
            val result = repository.save(mode, completeInitialChoice)
            if (result.isSuccess) withContext(Dispatchers.Main.immediate) { applier.apply(mode) }
            result
        }
    }
}

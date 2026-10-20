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
    const val IDENTITY = "nyanime_brand_identity"
    const val CLASSIC_UNLOCKED = "nyanime_classic_theme_unlocked"
}

enum class NyanimeIdentity { CLASSIC }

data class ThemeSettings(
    val mode: ThemeMode,
    val initialChoiceComplete: Boolean,
    val identity: NyanimeIdentity = NyanimeIdentity.CLASSIC,
    val classicUnlocked: Boolean = true,
)

interface ThemeSettingsRepository {
    val settings: Flow<ThemeSettings>
    fun current(): ThemeSettings
    suspend fun save(mode: ThemeMode, completeInitialChoice: Boolean = false): Result<Unit>
    suspend fun saveIdentity(identity: NyanimeIdentity): Result<Unit>
    suspend fun unlockClassic(): Result<Unit>
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

    suspend fun selectIdentity(identity: NyanimeIdentity): Result<Unit> = changes.withLock {
        withContext(NonCancellable) {
            val result = repository.saveIdentity(identity)
            // A palette change must not trigger Android night-mode recreation or restart playback.
            if (result.isSuccess) withContext(Dispatchers.Main.immediate) { reconcile() }
            result
        }
    }

    suspend fun unlockClassic(): Result<Unit> = changes.withLock {
        withContext(NonCancellable) { repository.unlockClassic() }
    }
}

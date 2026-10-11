package eu.kanade.tachiyomi.data.theme

import android.content.SharedPreferences
import eu.kanade.domain.ui.NyanimeIdentity
import eu.kanade.domain.ui.ThemePreferenceKeys
import eu.kanade.domain.ui.ThemeSettings
import eu.kanade.domain.ui.ThemeSettingsRepository
import eu.kanade.domain.ui.model.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** Application-owned adapter: observers see a choice only after its disk commit finishes. */
class AndroidThemeSettingsRepository(private val preferences: SharedPreferences) : ThemeSettingsRepository {
    private val updates = MutableStateFlow(readSettings())
    private val changes = Mutex()

    @Volatile private var writing = false

    override val settings = updates.asStateFlow()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (!writing && (key == null || key in keys)) {
            updates.value = readSettings()
        }
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    override fun current(): ThemeSettings = updates.value

    private fun readSettings(): ThemeSettings {
        val snapshot = preferences.all
        return ThemeSettings(
            mode = runCatching {
                ThemeMode.valueOf(snapshot[ThemePreferenceKeys.MODE] as? String ?: ThemeMode.SYSTEM.name)
            }.getOrDefault(ThemeMode.SYSTEM),
            initialChoiceComplete = snapshot[ThemePreferenceKeys.INITIAL_CHOICE] as? Boolean ?: false,
            identity = NyanimeIdentity.CLASSIC,
            classicUnlocked = true,
        )
    }

    override suspend fun save(mode: ThemeMode, completeInitialChoice: Boolean): Result<Unit> = persist {
        buildMap {
            put(ThemePreferenceKeys.MODE, mode.name)
            if (completeInitialChoice) put(ThemePreferenceKeys.INITIAL_CHOICE, true)
        }
    }

    override suspend fun saveIdentity(identity: NyanimeIdentity): Result<Unit> = persist {
        mapOf(ThemePreferenceKeys.IDENTITY to NyanimeIdentity.CLASSIC.name)
    }

    override suspend fun unlockClassic(): Result<Unit> = persist {
        mapOf(ThemePreferenceKeys.CLASSIC_UNLOCKED to true)
    }

    private suspend fun persist(values: (ThemeSettings) -> Map<String, Any>): Result<Unit> = changes.withLock {
        withContext(Dispatchers.IO) {
            writing = true
            try {
                runCatching {
                    val previous = current()
                    val changed = values(previous)
                    val editor = preferences.edit()
                    changed.forEach { (key, value) -> editor.putValue(key, value) }
                    if (!editor.commit()) {
                        // Android also mutates its in-memory map when a disk commit fails.
                        val rollback = preferences.edit()
                        val oldValues = mapOf(
                            ThemePreferenceKeys.MODE to previous.mode.name,
                            ThemePreferenceKeys.INITIAL_CHOICE to previous.initialChoiceComplete,
                            ThemePreferenceKeys.IDENTITY to previous.identity.name,
                            ThemePreferenceKeys.CLASSIC_UNLOCKED to previous.classicUnlocked,
                        )
                        changed.keys.forEach { rollback.putValue(it, oldValues.getValue(it)) }
                        rollback.commit()
                        throw IOException("Unable to persist theme selection")
                    }
                }
            } finally {
                writing = false
                updates.value = readSettings()
            }
        }
    }

    private fun SharedPreferences.Editor.putValue(key: String, value: Any) {
        when (value) {
            is String -> putString(key, value)
            is Boolean -> putBoolean(key, value)
            else -> error("Unsupported appearance preference")
        }
    }

    private companion object {
        val keys = setOf(
            ThemePreferenceKeys.MODE,
            ThemePreferenceKeys.INITIAL_CHOICE,
            ThemePreferenceKeys.IDENTITY,
            ThemePreferenceKeys.CLASSIC_UNLOCKED,
        )
    }
}

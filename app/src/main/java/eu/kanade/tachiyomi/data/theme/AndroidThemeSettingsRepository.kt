package eu.kanade.tachiyomi.data.theme

import android.content.SharedPreferences
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
        if (!writing && (key == null || key == ThemePreferenceKeys.MODE || key == ThemePreferenceKeys.INITIAL_CHOICE)) {
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
        )
    }

    override suspend fun save(mode: ThemeMode, completeInitialChoice: Boolean): Result<Unit> = changes.withLock {
        withContext(Dispatchers.IO) {
            writing = true
            try {
                runCatching {
                    val previous = current()
                    val editor = preferences.edit().putString(ThemePreferenceKeys.MODE, mode.name)
                    if (completeInitialChoice) editor.putBoolean(ThemePreferenceKeys.INITIAL_CHOICE, true)
                    if (!editor.commit()) {
                        // Android also mutates its in-memory map when a disk commit fails.
                        preferences.edit().putString(ThemePreferenceKeys.MODE, previous.mode.name)
                            .putBoolean(ThemePreferenceKeys.INITIAL_CHOICE, previous.initialChoiceComplete).commit()
                        throw IOException("Unable to persist theme selection")
                    }
                }
            } finally {
                writing = false
                updates.value = readSettings()
            }
        }
    }
}

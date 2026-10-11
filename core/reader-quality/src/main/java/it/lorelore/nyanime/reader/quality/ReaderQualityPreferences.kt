package it.lorelore.nyanime.reader.quality

import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/** Overrides are independent of manga viewer flags, so old clients cannot reinterpret them. */
class ReaderQualityPreferences(private val preferences: SharedPreferences) {
    private val defaultKey = "reader_quality_default_v1"
    private fun titleKey(title: QualityTitle) = "reader_quality_title_v1_${title.storageKey}"

    fun global(): QualityEngineId = engine(preferences.getString(defaultKey, null))
    fun override(title: QualityTitle): QualityEngineId? = preferences.getString(titleKey(title), null)?.let(::engine)
    fun selected(title: QualityTitle?): QualityEngineId = title?.let(::override) ?: global()
    fun setGlobal(engine: QualityEngineId) {
        preferences.edit().putString(defaultKey, engine.name).apply()
    }
    fun setOverride(title: QualityTitle, engine: QualityEngineId?) {
        preferences.edit().apply {
            if (engine == null) remove(titleKey(title)) else putString(titleKey(title), engine.name)
        }.apply()
    }

    fun observe(title: QualityTitle?) = callbackFlow {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == defaultKey || key == title?.let(::titleKey)) trySend(selected(title))
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(selected(title))
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate()

    private fun engine(value: String?) = QualityEngineId.entries.find { it.name == value } ?: QualityEngineId.OFF
}

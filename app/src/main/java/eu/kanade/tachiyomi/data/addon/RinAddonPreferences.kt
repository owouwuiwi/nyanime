package eu.kanade.tachiyomi.data.addon

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Addon preferences live under noBackupFilesDir, outside app preferences and exports. */
internal class RinAddonPreferences private constructor(file: File) : SharedPreferences {
    private val file = AtomicFile(file)
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    private var state: MutableMap<String, Any> = if (!this.file.baseFile.exists() &&
        !File(this.file.baseFile.path + ".bak").exists()
    ) {
        mutableMapOf()
    } else {
        val json = JSONObject(this.file.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
        json.keys().asSequence().associateWith { key ->
            json.get(key).let { value ->
                if (value is JSONArray) (0 until value.length()).map { value.getString(it) }.toSet() else value
            }
        }.toMutableMap()
    }

    @Synchronized override fun getAll(): MutableMap<String, *> = state.toMutableMap()

    @Synchronized override fun getString(key: String?, defValue: String?) = state[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    @Synchronized
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (state[key] as? Set<String>)?.toMutableSet() ?: defValues?.toMutableSet()

    @Synchronized override fun getInt(key: String?, defValue: Int) = (state[key] as? Number)?.toInt() ?: defValue

    @Synchronized override fun getLong(key: String?, defValue: Long) = (state[key] as? Number)?.toLong() ?: defValue

    @Synchronized override fun getFloat(key: String?, defValue: Float) = (state[key] as? Number)?.toFloat() ?: defValue

    @Synchronized override fun getBoolean(key: String?, defValue: Boolean) = state[key] as? Boolean ?: defValue

    @Synchronized override fun contains(key: String?) = state.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()

    @Synchronized override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        if (listener !=
            null
        ) {
            listeners.add(listener)
        }
    }

    @Synchronized override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        listeners.remove(listener)
    }
    private inner class Editor : SharedPreferences.Editor {
        private val updates = mutableMapOf<String, Any?>()
        private var clear = false
        override fun putString(key: String?, value: String?) = apply { updates[requireNotNull(key)] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = apply {
            updates[requireNotNull(key)] =
                values?.toSet()
        }
        override fun putInt(key: String?, value: Int) = apply { updates[requireNotNull(key)] = value }
        override fun putLong(key: String?, value: Long) = apply { updates[requireNotNull(key)] = value }
        override fun putFloat(key: String?, value: Float) = apply { updates[requireNotNull(key)] = value }
        override fun putBoolean(key: String?, value: Boolean) = apply { updates[requireNotNull(key)] = value }
        override fun remove(key: String?) = apply { updates[requireNotNull(key)] = null }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean = synchronized(this@RinAddonPreferences) {
            val next = if (clear) mutableMapOf() else state.toMutableMap()
            updates.forEach { (key, value) -> if (value == null) next.remove(key) else next[key] = value }
            val changed = (state.keys + next.keys).filter { state[it] != next[it] }
            val output = file.startWrite()
            try {
                val json = JSONObject()
                next.forEach { (key, value) ->
                    json.put(key, if (value is Set<*>) JSONArray(value.toList()) else value)
                }
                output.write(json.toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(output)
                state = next
            } catch (_: Exception) {
                file.failWrite(output)
                return false
            }
            val notify = listeners.toList()
            Handler(Looper.getMainLooper()).post {
                changed.forEach { key ->
                    notify.forEach { it.onSharedPreferenceChanged(this@RinAddonPreferences, key) }
                }
            }
            true
        }
        override fun apply() {
            check(commit())
        }
    }
    companion object {
        private val instances = mutableMapOf<String, RinAddonPreferences>()

        @Synchronized fun get(file: File) = instances.getOrPut(file.absolutePath) { RinAddonPreferences(file) }
    }
}

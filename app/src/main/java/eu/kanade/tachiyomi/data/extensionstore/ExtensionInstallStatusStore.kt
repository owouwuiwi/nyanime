package eu.kanade.tachiyomi.data.extensionstore

import android.content.Context
import eu.kanade.tachiyomi.extension.InstallStep
import org.json.JSONObject

/** A process restart cannot turn an interrupted transfer into a reported successful installation. */
internal class ExtensionInstallStatusStore(context: Context) {
    private val preferences = context.getSharedPreferences("extension_install_status_v1", Context.MODE_PRIVATE)

    @Synchronized fun read(): Map<String, InstallStep> = preferences.all.mapNotNull { (name, raw) ->
        runCatching {
            val data = JSONObject(raw as String)
            val step = InstallStep.valueOf(data.getString("step"))
            name to when (step) {
                InstallStep.Pending, InstallStep.Downloading -> InstallStep.Error
                else -> step
            }
        }.getOrNull()
    }.toMap()
    fun expectedVersion(packageName: String): Long? = runCatching {
        JSONObject(requireNotNull(preferences.getString(packageName, null))).optLong("version", 0).takeIf { it > 0 }
    }.getOrNull()

    fun expectedMode(packageName: String): StoreInstallMode? = runCatching {
        val data = JSONObject(requireNotNull(preferences.getString(packageName, null)))
        StoreInstallMode.valueOf(data.getString("mode"))
    }.getOrNull()

    @Synchronized fun set(
        packageName: String,
        step: InstallStep,
        expectedVersion: Long? = null,
        mode: StoreInstallMode? = null,
    ) {
        val data = runCatching {
            JSONObject(requireNotNull(preferences.getString(packageName, null)))
        }.getOrDefault(JSONObject())
        expectedVersion?.let { data.put("version", it) }
        mode?.let { data.put("mode", it.name) }
        preferences.edit().putString(
            packageName,
            data.put("step", step.name).put("at", System.currentTimeMillis()).toString(),
        ).apply()
    }

    @Synchronized fun forget(packageName: String) {
        preferences.edit().remove(packageName).apply()
    }
}

package eu.kanade.tachiyomi.data.extensionstore

import android.content.Context
import eu.kanade.tachiyomi.data.rin.RinArchive
import eu.kanade.tachiyomi.data.rin.RinEntry
import eu.kanade.tachiyomi.extension.InstallStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.json.JSONObject

@Serializable
internal data class ApprovedBridgeInstall(
    val basePackage: String,
    val medium: StoreMedium,
    val version: Long,
    val signer: String,
    val mode: StoreInstallMode,
    val bridges: List<RinEntry>,
    val createdAt: Long,
) {
    fun matches(base: StoreEntry): Boolean = base.installed && base.enabled &&
        base.packageName == basePackage && base.medium == medium && base.action != StoreAction.TRUST &&
        base.installedVersion == version && base.imported == (mode == StoreInstallMode.PRIVATE) &&
        base.installedMetadata?.let { it.signers.size == 1 && signer in (it.signers + it.signerHistory) } == true

    fun compatibleBridges(base: StoreEntry, current: List<StoreEntry>): List<StoreEntry> {
        if (!matches(base)) return emptyList()
        val approved = current.filter { it.rinCandidate in bridges && !it.installed }
        val related = StoreBridges.bySource(listOf(base) + approved, emptyList(), adult = true)
        return StoreBridges.forInstallation(related[base.id].orEmpty())
    }
}

/** A process restart cannot turn an interrupted transfer into a reported successful installation. */
internal class ExtensionInstallStatusStore(context: Context) {
    private val preferences = context.getSharedPreferences("extension_install_status_v1", Context.MODE_PRIVATE)
    private val mutableBridgeInstalls = MutableStateFlow(
        preferences.all.filterKeys { it.startsWith(BRIDGE_PREFIX) }.mapNotNull { (_, raw) ->
            runCatching { RinArchive.json.decodeFromString<ApprovedBridgeInstall>(raw as String) }.getOrNull()
        }.associateBy { it.basePackage },
    )
    val bridgeInstalls = mutableBridgeInstalls.asStateFlow()

    @Synchronized fun read(): Map<String, InstallStep> = preferences.all.filterKeys {
        !it.startsWith(BRIDGE_PREFIX)
    }.mapNotNull { (name, raw) ->
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

    @Synchronized fun approveBridges(plan: ApprovedBridgeInstall) {
        val payload = RinArchive.json.encodeToString(plan)
        check(preferences.edit().putString(BRIDGE_PREFIX + plan.basePackage, payload).commit())
        mutableBridgeInstalls.update { it + (plan.basePackage to plan) }
    }

    @Synchronized fun clearBridgeApproval(plan: ApprovedBridgeInstall) {
        if (mutableBridgeInstalls.value[plan.basePackage] != plan) return
        preferences.edit().remove(BRIDGE_PREFIX + plan.basePackage).commit()
        mutableBridgeInstalls.update { it - plan.basePackage }
    }

    private companion object {
        const val BRIDGE_PREFIX = "bridge:"
    }
}

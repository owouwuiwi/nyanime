package eu.kanade.tachiyomi.data.addon

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import eu.kanade.domain.extension.AddonActivation
import eu.kanade.domain.extension.AddonHostVersions
import eu.kanade.domain.extension.ExtensionPackageMetadata
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.extension.ExtensionPackageDiscovery
import eu.kanade.tachiyomi.extension.ExtensionPackageInspector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.api.get

data class AddonExtension(
    val packageName: String,
    val label: String,
    val version: String,
    val versionCode: Long,
    val metadata: ExtensionPackageMetadata,
    val compatible: Boolean,
    val trusted: Boolean,
    val enabled: Boolean,
    val entryClass: String?,
    val logo: Int,
    val activation: AddonActivation?,
    val sharesAppearance: Boolean,
    val sourceUrl: String?,
    val api: Int = 1,
    val actionLabel: String? = null,
    val actionSummary: String? = null,
    val description: String? = null,
    val hostVersions: AddonHostVersions = AddonHostVersions(),
    val dataRecovery: Boolean = false,
    val standalone: Boolean = false,
) {
    val usable get() = compatible && trusted && enabled
}

/** Only inspects package resources and metadata. No class loader or foreign initialization. */
class AddonRegistry(private val context: Context) {
    private val mutex = Mutex()
    private val storage = AddonStateStore(context)
    private val mutable = MutableStateFlow<List<AddonExtension>>(emptyList())
    val state = mutable.asStateFlow()

    @Suppress("DEPRECATION")
    suspend fun reload() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val preferences = storage.snapshot()
            val flags = PackageManager.GET_CONFIGURATIONS or PackageManager.GET_META_DATA or
                PackageManager.GET_SIGNATURES or
                if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0
            val pm = context.packageManager
            mutable.value = ExtensionPackageDiscovery.candidates(context, flags, setOf(FEATURE)).filter { info ->
                info.reqFeatures?.any { it.name == FEATURE } == true
            }.mapNotNull { info ->
                runCatching {
                    val app = requireNotNull(info.applicationInfo)
                    val metadata = ExtensionPackageInspector.inspect(info) { 0 }
                    val data = app.metaData
                    val api = data?.getInt(API, 0) ?: 0
                    val entries = pm.queryIntentActivities(Intent(OPEN).setPackage(info.packageName), 0)
                        .map { it.activityInfo }.filter { it.exported && it.enabled && it.permission == null }
                    val entry = entries.singleOrNull()
                    val resources = context.createPackageContext(info.packageName, 0)
                        .createConfigurationContext(context.resources.configuration).resources
                    val activation = AddonActivation.parse(
                        data?.getString("nyanime.addon.activation"),
                        data?.getInt("nyanime.addon.hold_ms", 0)?.toLong() ?: 0,
                        api,
                    )
                    fun declaredText(name: String, max: Int): String? = runCatching {
                        val id = data?.getInt(name, 0) ?: 0
                        require(resources.getResourceTypeName(id) == "string")
                        resources.getString(id).takeIf { it.isNotBlank() && it.length <= max }
                    }.getOrNull()
                    val actionLabel = if (activation?.isMoreAction == true) {
                        declaredText("nyanime.addon.action_label", 128)
                    } else {
                        null
                    }
                    val summary = declaredText("nyanime.addon.action_summary", 256)
                    val description = declaredText("nyanime.addon.description", 4096)
                    val logo = data?.getInt("nyanime.addon.logo", 0) ?: 0
                    val logoValid =
                        logo != 0 &&
                            runCatching { pm.getResourcesForApplication(app).getDrawable(logo, null) }.isSuccess
                    val hostVersions = hostVersions(data)
                    val compatible = hostVersions.supports(BuildConfig.VERSION_CODE.toLong()) &&
                        api in 1..2 &&
                        activation != null &&
                        (!activation.isMoreAction || (actionLabel != null && summary != null && description != null)) &&
                        logoValid &&
                        entry != null &&
                        app.enabled &&
                        !metadata.invalidDistribution &&
                        metadata.distribution?.id != null
                    val pins = preferences.optJSONArray("${info.packageName}.signers")
                    val approved = if (pins ==
                        null
                    ) {
                        emptySet()
                    } else {
                        (0 until pins.length()).map(pins::getString).toSet()
                    }
                    val execution = eu.kanade.domain.extension.ExtensionExecutionTrust(uy.kohesive.injekt.Injekt.get())
                    val trusted =
                        execution.permits(info.packageName, metadata) ||
                            execution.allowsLegacy(info.packageName) &&
                            AddonActivation.trusted(
                                approved,
                                metadata.signers,
                                metadata.signerHistory,
                                preferences.optString("${info.packageName}.distribution").takeIf { it.isNotEmpty() },
                                metadata.distribution?.id,
                            )
                    AddonExtension(
                        info.packageName, pm.getApplicationLabel(app).toString(),
                        info.versionName.orEmpty(), PackageInfoCompat.getLongVersionCode(info), metadata,
                        compatible, trusted,
                        preferences.optBoolean(
                            "${info.packageName}.enabled",
                            eu.kanade.domain.extension.ExtensionExecutionTrust(
                                uy.kohesive.injekt.Injekt.get(),
                            ).permits(info.packageName, metadata),
                        ),
                        entry?.name, logo, activation, data?.getBoolean("nyanime.addon.appearance", false) == true,
                        data?.getString("nyanime.addon.source")?.takeIf { url ->
                            runCatching {
                                val uri = java.net.URI(url)
                                uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
                            }.getOrDefault(false)
                        },
                        api, actionLabel, summary, description, hostVersions,
                        api == 2 && data?.getBoolean("nyanime.addon.data_recovery", false) == true,
                        api == 2 && data?.getBoolean("nyanime.addon.standalone", false) == true,
                    )
                }.getOrNull()
            }.sortedBy { it.label }
        }
    }

    suspend fun trust(packageName: String) {
        reload()
        val addon = state.value.singleOrNull { it.packageName == packageName && it.compatible } ?: return
        withContext(Dispatchers.IO) {
            eu.kanade.domain.extension.ExtensionExecutionTrust(uy.kohesive.injekt.Injekt.get())
                .approve(packageName, addon.metadata)
            storage.update {
                it.put("$packageName.signers", org.json.JSONArray(addon.metadata.signers.toList()))
                    .put("$packageName.distribution", addon.metadata.distribution?.id)
                    .put("$packageName.enabled", true)
            }
        }
        reload()
    }

    suspend fun enable(packageName: String, enabled: Boolean) {
        reload()
        val addon = state.value.singleOrNull { it.packageName == packageName && it.compatible && it.trusted } ?: return
        withContext(Dispatchers.IO) {
            storage.update { it.put("${addon.packageName}.enabled", enabled) }
            if (!enabled) eu.kanade.tachiyomi.data.recovery.RecoveryAccess(context).revoke(packageName)
        }
        reload()
    }

    companion object {
        @Suppress("DEPRECATION")
        internal fun hostVersions(data: android.os.Bundle?): AddonHostVersions {
            fun code(key: String): Long? = if (data?.containsKey(key) == true) {
                (data.get(key) as? Int)?.toLong() ?: -1L
            } else {
                null
            }
            fun name(key: String): String? = if (data?.containsKey(key) == true) {
                data.get(key) as? String ?: ""
            } else {
                null
            }
            return AddonHostVersions(
                code("nyanime.addon.min_host_version_code"),
                code("nyanime.addon.max_host_version_code"),
                name("nyanime.addon.min_host_version_name"),
                name("nyanime.addon.max_host_version_name"),
            )
        }

        const val FEATURE = "nyanime.addon"
        const val API = "nyanime.addon.api"
        const val OPEN = "nyanime.addon.OPEN"
    }
}

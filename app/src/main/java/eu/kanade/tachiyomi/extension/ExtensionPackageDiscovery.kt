package eu.kanade.tachiyomi.extension

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Discover declared contracts with small parcels; retrieve metadata only for matching packages. */
object ExtensionPackageDiscovery {
    private data class Declaration(val name: String, val features: Set<String>)
    private val contracts = setOf(
        "tachiyomi.animeextension",
        "tachiyomi.extension",
        "nyanime.newsextension",
        "nyanime.tvextension",
        "nyanime.guideextension",
        "nyanime.addon",
    )
    private var cache: ExtensionInventoryCache<Declaration>? = null
    private val details = mutableMapOf<Pair<String, Int>, PackageInfo>()
    private val detailFailures = mutableSetOf<Pair<String, Int>>()
    private val existenceFailures = mutableSetOf<String>()
    private val mutableUnavailable = MutableStateFlow(false)
    val unavailable = mutableUnavailable.asStateFlow()

    @Synchronized
    fun invalidate() {
        cache?.invalidate()
    }

    @Suppress("DEPRECATION")
    @Synchronized
    fun candidates(context: Context, flags: Int, features: Set<String>): List<PackageInfo> {
        val preferences = context.getSharedPreferences("extension_discovery_cache_v1", Context.MODE_PRIVATE)
        val inventory = cache ?: ExtensionInventoryCache(
            clock = SystemClock::elapsedRealtime,
            initial = runCatching {
                preferences.getString("declarations", null)?.let { raw ->
                    require(raw.length <= 256 * 1024)
                    Json.parseToJsonElement(raw).jsonObject.map { (name, value) ->
                        require(name.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")))
                        Declaration(name, value.jsonArray.map { it.jsonPrimitive.content }.toSet().intersect(contracts))
                    }
                }
            }.getOrNull(),
        ).also { cache = it }
        val snapshot = inventory.read {
            val complete = context.packageManager.getInstalledPackages(PackageManager.GET_CONFIGURATIONS)
                .mapNotNull { info ->
                    val declared = info.reqFeatures.orEmpty().mapNotNull { it.name }.toSet().intersect(contracts)
                    if (declared.isEmpty()) null else Declaration(info.packageName, declared)
                }
            val encoded = buildJsonObject {
                complete.forEach { item ->
                    put(item.name, JsonArray(item.features.map(::JsonPrimitive)))
                }
            }
            preferences.edit().putString("declarations", encoded.toString()).apply()
            complete
        }
        if (!snapshot.unavailable) {
            val present = snapshot.items.map { it.name }.toSet()
            details.keys.retainAll { it.first in present }
            detailFailures.retainAll { it.first in present }
        }
        val result = snapshot.items.filter { it.features.any(features::contains) }.mapNotNull { item ->
            val key = item.name to flags
            try {
                context.packageManager.getPackageInfo(item.name, flags).also {
                    details[key] = it
                    detailFailures.remove(key)
                }
            } catch (_: PackageManager.NameNotFoundException) {
                details.remove(key)
                detailFailures.remove(key)
                null
            } catch (_: RuntimeException) {
                detailFailures.add(key)
                details[key]
            }
        }
        mutableUnavailable.value = snapshot.unavailable || detailFailures.isNotEmpty() || existenceFailures.isNotEmpty()
        return result
    }

    @Suppress("DEPRECATION")
    @Synchronized
    fun retryFailed(context: Context) {
        detailFailures.toList().forEach { key ->
            try {
                details[key] = context.packageManager.getPackageInfo(key.first, key.second)
                detailFailures.remove(key)
            } catch (_: PackageManager.NameNotFoundException) {
                details.remove(key)
                detailFailures.remove(key)
            } catch (_: RuntimeException) {
                // Retain the previous metadata and retry with the repository's backoff.
            }
        }
        existenceFailures.toList().forEach { exists(context, it) }
    }

    @Suppress("DEPRECATION")
    @Synchronized
    fun exists(context: Context, name: String): Boolean? = try {
        context.packageManager.getApplicationInfo(name, 0)
        existenceFailures.remove(name)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        existenceFailures.remove(name)
        false
    } catch (_: RuntimeException) {
        existenceFailures.add(name)
        mutableUnavailable.value = true
        null
    }
}

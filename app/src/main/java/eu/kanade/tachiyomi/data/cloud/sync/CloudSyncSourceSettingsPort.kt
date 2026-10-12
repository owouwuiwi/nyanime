package eu.kanade.tachiyomi.data.cloud.sync

import android.content.Context
import android.content.SharedPreferences
import eu.kanade.tachiyomi.data.rin.RinRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import nyanime.telegram.sync.SyncCheckpoints
import nyanime.telegram.sync.SyncPreferenceContract
import nyanime.telegram.sync.SyncPreferenceRule
import nyanime.telegram.sync.SyncRef
import nyanime.telegram.sync.syncJson
import java.util.zip.ZipFile

/** No extension implementation is executed to discover portability; only its signed asset is read. */
internal class CloudSyncSourceSettingsPort(
    private val context: Context,
    private val store: CloudSyncStore,
    private val preferences: CloudSyncPreferences,
    private val now: () -> Long,
    private val allowCredentials: () -> Boolean = { false },
) : CloudSyncDomainPort {
    private val rin = RinRepository.get(context)
    private var inventory = ""
    private var bindings = emptyMap<SyncRef, SyncPreferenceRule>()
    private val observed = mutableMapOf<SyncRef, JsonElement?>()
    private var policy: String? = null
    private var invalidContracts = 0
    private val invalidValues = mutableSetOf<SyncRef>()
    override fun blockedCount(): Int = invalidContracts + invalidValues.size

    override suspend fun configure(enabled: Boolean, incognito: Boolean, excludedSources: Set<Long>) = Unit
    override suspend fun seed() {
        capture()
    }

    private fun refresh() {
        val installed = rin.installed.value.filter { it.manifest.medium in setOf("anime", "manga") }
        val fingerprint = installed.joinToString { it.sha256 }
        if (fingerprint == inventory) return
        val result = mutableMapOf<SyncRef, SyncPreferenceRule>()
        invalidContracts = 0
        invalidValues.clear()
        for (extension in installed) {
            val contract = try {
                ZipFile(rin.storage.file(extension)).use { zip ->
                    val asset = zip.getEntry(SyncPreferenceContract.ASSET) ?: return@use null
                    require(asset.size in 1..131_072)
                    val bytes = zip.getInputStream(asset).use { RinRepository.boundedRead(it, 131_072) }
                    syncJson.decodeFromString<SyncPreferenceContract>(bytes.toString(Charsets.UTF_8))
                        .also { it.validate(extension.manifest.knownSourceIds) }
                }
            } catch (_: Exception) {
                // A broken optional capability must not stop library/progress synchronization.
                invalidContracts++
                null
            } ?: continue
            contract.settings.forEach { rule ->
                val kind = extension.manifest.medium + if (rule.credential) "-source-credential" else "-source-setting"
                val ref = SyncRef(kind, rule.source, rule.key, extension.signer)
                require(result.put(ref, rule) == null)
            }
        }
        bindings = result
        inventory = fingerprint
        observed.clear()
    }

    private fun local(rule: SyncPreferenceRule): SharedPreferences =
        context.getSharedPreferences("source_${rule.source}", Context.MODE_PRIVATE)

    override suspend fun capture(limit: Long): Int {
        if (!preferences.sharedCatalogues().get()) return 0
        val currentPolicy = store.state("exclusions")
        if (currentPolicy != policy) {
            observed.clear()
            policy = currentPolicy
        }
        refresh()
        var changes = 0
        for ((ref, rule) in bindings) {
            if (store.isExcluded(ref) || rule.credential && !allowCredentials()) continue
            val local = local(rule)
            val value = try {
                encode(local.all[rule.key]).also(rule::validate)
            } catch (_: Exception) {
                invalidValues += ref
                continue
            }
            invalidValues -= ref
            if (observed.containsKey(ref) && observed[ref] == value) continue
            val intentKey = "source-setting-intent/${ref.key()}"
            store.state(intentKey)?.takeIf { it.isNotEmpty() }?.let {
                val intent = syncJson.decodeFromString<CloudSyncStore.Projection>(it)
                val fields = intent.record.fields.mapValues { field -> field.value.resolved()?.value }
                if (value == desired(rule, fields)) store.applied(intent, fields)
                store.state(intentKey, "")
            }
            if (value != null || store.record(ref) != null) {
                check(local.edit().commit()) // Capture only after pending local writes reach disk.
                val fields = if (rule.type == "strings") {
                    val members = (value as? JsonArray).orEmpty().associate {
                        "member/${SyncCheckpoints.hash(it.jsonPrimitive.content)}" to it
                    }
                    (members.keys + store.baselineFields(ref)).associateWith { members[it] }
                } else {
                    mapOf("value" to value)
                }
                changes += store.capture(ref, fields, now())
            }
            observed[ref] = value
        }
        return changes
    }

    override suspend fun apply(projection: CloudSyncStore.Projection): Boolean {
        if (!projection.record.ref.kind.contains("-source-")) return false
        if (!preferences.sharedCatalogues().get()) return false
        refresh()
        val ref = projection.record.ref
        val rule = bindings[ref] ?: return false // Requires the same source identity and verified signer.
        if (store.isExcluded(ref) || rule.credential && !allowCredentials()) return false
        capture()
        if (store.record(ref) != projection.record) return false
        val fields = projection.record.fields.mapValues { it.value.resolved()?.value }
        val desired = desired(rule, fields)
        try {
            rule.validate(desired)
        } catch (_: Exception) {
            invalidValues += ref
            return false
        }
        val local = local(rule)
        val before = encode(local.all[rule.key])
        val intentKey = "source-setting-intent/${ref.key()}"
        store.state(intentKey, syncJson.encodeToString(projection))
        val written = withContext(Dispatchers.Main.immediate) {
            if (encode(local.all[rule.key]) != before) {
                false
            } else {
                val editor = local.edit()
                if (desired == null || desired == JsonNull) {
                    editor.remove(rule.key)
                } else {
                    when (rule.type) {
                        "boolean" -> editor.putBoolean(rule.key, desired.jsonPrimitive.boolean)
                        "int" -> editor.putInt(rule.key, desired.jsonPrimitive.int)
                        "long" -> editor.putLong(rule.key, desired.jsonPrimitive.long)
                        "float" -> {
                            val number = desired.jsonPrimitive.float
                            require(number.isFinite())
                            editor.putFloat(rule.key, number)
                        }
                        "string" -> editor.putString(rule.key, desired.jsonPrimitive.content)
                        "strings" -> {
                            val members = (desired as JsonArray).map { it.jsonPrimitive.content }.toSet()
                            editor.putStringSet(rule.key, members)
                        }
                    }
                }
                editor.apply()
                true
            }
        }
        if (!written) return false
        withContext(Dispatchers.IO) { check(local.edit().commit()) }
        store.applied(projection, fields)
        store.state(intentKey, "")
        observed[ref] = desired
        return true
    }

    private fun desired(rule: SyncPreferenceRule, fields: Map<String, JsonElement?>): JsonElement? =
        if (rule.type == "strings") {
            JsonArray(
                fields.filterKeys { it.startsWith("member/") }.mapNotNull { (key, value) ->
                    value?.takeUnless { it == JsonNull }?.also {
                        require(key == "member/${SyncCheckpoints.hash(it.jsonPrimitive.content)}")
                    }
                }.sortedBy { it.jsonPrimitive.content },
            )
        } else {
            fields["value"]
        }

    private fun encode(value: Any?): JsonElement? = when (value) {
        null -> null
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Set<*> -> JsonArray(value.filterIsInstance<String>().sorted().map(::JsonPrimitive))
        else -> error("Unsupported declared source preference")
    }
}

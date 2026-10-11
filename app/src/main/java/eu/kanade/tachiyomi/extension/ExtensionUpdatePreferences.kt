package eu.kanade.tachiyomi.extension

import eu.kanade.domain.extension.ExtensionPackageMetadata
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getAndSet

/** Portable settings keyed by package AND signer. Restored bindings never bypass live identity checks. */
class ExtensionUpdatePreferences(store: PreferenceStore, private val kind: String) {
    private val records = store.getStringSet("nyanime_extension_policies_" + kind, emptySet())
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Record(
        val packageName: String,
        val signers: Set<String>,
        val keep: Boolean,
        val repository: String?,
    )

    private fun read() = records.get().mapNotNull { runCatching { json.decodeFromString<Record>(it) }.getOrNull() }

    init {
        // Clear retired retention flags, including records restored from an older backup.
        if (read().any { it.keep }) {
            records.getAndSet { old ->
                old.map { raw ->
                    runCatching { json.encodeToString(json.decodeFromString<Record>(raw).copy(keep = false)) }
                        .getOrDefault(raw)
                }.toSet()
            }
        }
    }
    private fun find(packageName: String, metadata: ExtensionPackageMetadata) = read().singleOrNull {
        it.packageName == packageName && it.signers == metadata.signers && it.signers.isNotEmpty()
    }

    // Retired preference: older backups cannot freeze updates again.
    @Suppress("UNUSED_PARAMETER")
    fun keep(packageName: String, metadata: ExtensionPackageMetadata) = false
    fun repository(packageName: String, metadata: ExtensionPackageMetadata) = find(packageName, metadata)?.repository

    @Synchronized
    fun bind(packageName: String, metadata: ExtensionPackageMetadata, repository: String) {
        save(packageName, metadata, false, repository)
    }

    @Synchronized
    private fun save(packageName: String, metadata: ExtensionPackageMetadata, keep: Boolean, repository: String?) {
        if (metadata.signers.isEmpty()) return
        if (find(packageName, metadata) == Record(packageName, metadata.signers, keep, repository)) return
        records.getAndSet { old ->
            val retained = old.filterNot { raw ->
                runCatching { json.decodeFromString<Record>(raw).packageName == packageName }.getOrDefault(true)
            }
            (retained + json.encodeToString(Record(packageName, metadata.signers, keep, repository))).toSet()
        }
    }
}

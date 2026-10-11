package eu.kanade.domain.extension

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/** Consent for a selected, verified package, independent of Android/private installation. */
class ExtensionExecutionTrust(store: PreferenceStore) {
    private val preference = store.getString(Preference.appStateKey("extension_execution_trust_v1"), "[]")
    private val legacyRevoked = store.getBoolean(Preference.appStateKey("extension_execution_legacy_revoked_v1"), false)
    private val catalogueTrust = ExtensionCatalogueTrust(store)
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable data class Approval(
        val packageName: String,
        val signers: Set<String>,
        val repository: String? = null,
        val catalogueKey: String? = null,
    )
    private fun approvals() = runCatching {
        require(preference.get().length <= 256 * 1024)
        json.decodeFromString<List<Approval>>(preference.get())
    }.getOrDefault(emptyList())

    /** Old per-family settings must not turn revocable catalogue consent into individual consent. */
    fun delegated(packageName: String): Boolean = approvals().any {
        it.packageName == packageName && it.catalogueKey != null
    }
    fun allowsLegacy(packageName: String): Boolean = !legacyRevoked.get() && !delegated(packageName)
    fun permits(packageName: String, metadata: ExtensionPackageMetadata): Boolean = approvals().any {
        it.packageName == packageName &&
            it.signers.isNotEmpty() &&
            (
                it.signers == metadata.signers ||
                    it.signers.size == 1 &&
                    metadata.signers.size == 1 &&
                    metadata.signerHistory.containsAll(it.signers)
                ) &&
            (it.catalogueKey == null || catalogueTrust.permits(it.repository, it.catalogueKey))
    }
    fun approve(
        packageName: String,
        metadata: ExtensionPackageMetadata,
        repository: String? = null,
        catalogueKey: String? = null,
    ) = synchronized(lock) {
        require(metadata.signers.isNotEmpty() && metadata.signers.all { it.matches(Regex("[a-f0-9]{64}")) })
        require(catalogueKey == null || catalogueTrust.permits(repository, catalogueKey))
        val old = approvals().firstOrNull { it.packageName == packageName }
        val approval = Approval(
            packageName,
            metadata.signers,
            repository,
            if (old?.catalogueKey == null && old != null) null else catalogueKey,
        )
        preference.set(json.encodeToString(approvals().filterNot { it.packageName == packageName } + approval))
    }
    fun remove(packageName: String) = synchronized(lock) {
        preference.set(json.encodeToString(approvals().filterNot { it.packageName == packageName }))
    }
    fun revokeAll() = synchronized(lock) {
        preference.set("[]")
        legacyRevoked.set(true)
    }
    private companion object {
        val lock = Any()
    }
}

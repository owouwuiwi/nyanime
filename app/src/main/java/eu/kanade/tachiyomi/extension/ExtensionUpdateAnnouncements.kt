package eu.kanade.tachiyomi.extension

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

internal data class ExtensionUpdate(
    val packageName: String,
    val versionCode: Long,
    val libVersion: Double,
    val name: String,
    val repository: String? = null,
    val signer: String? = null,
) {
    val identity: String get() = packageName +
        "|" +
        versionCode +
        "|" +
        libVersion +
        "|" +
        repository.orEmpty() +
        "|" +
        signer.orEmpty()
}

/** Remember versions, not counts: dismissing an alert is not permission to show it again. */
internal class ExtensionUpdateAnnouncements(preferences: PreferenceStore, kind: ExtensionUpdateKind) {
    private val announced = preferences.getStringSet(
        Preference.appStateKey("extension_update_" + kind.key + "_announced"),
        emptySet(),
    )

    fun hasNew(updates: List<ExtensionUpdate>): Boolean = updates.any { it.identity !in announced.get() }

    fun record(updates: List<ExtensionUpdate>) {
        val packages = updates.map { it.packageName }.toSet()
        // Keep absent packages: a temporarily unavailable repo must not cause repeat notifications.
        val retained = announced.get().filterNot { it.substringBefore('|') in packages }
        announced.set((retained + updates.map { it.identity }).takeLast(1024).toSet())
    }
}

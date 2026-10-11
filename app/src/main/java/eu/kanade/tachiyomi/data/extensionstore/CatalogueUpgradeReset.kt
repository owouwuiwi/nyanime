package eu.kanade.tachiyomi.data.extensionstore

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/** Installation-local, including upgrades from legacy revision codes larger than public version codes. */
class CatalogueUpgradeReset(store: PreferenceStore) {
    private val completed = store.getBoolean(Preference.appStateKey("catalogue_upgrade_reset_v1"), false)
    val notice = store.getBoolean(Preference.appStateKey("catalogue_upgrade_reset_notice_v1"), false)

    suspend fun initialize(previousVersion: Int, clearCatalogues: suspend () -> Unit) {
        if (completed.get()) return
        // A fresh installation is marked immediately; later imports/restores must not trigger a reset.
        if (previousVersion == 0) {
            completed.set(true)
            return
        }
        withContext(NonCancellable) {
            clearCatalogues()
            notice.set(true)
            completed.set(true)
        }
    }

    fun acknowledge() = notice.set(false)
}

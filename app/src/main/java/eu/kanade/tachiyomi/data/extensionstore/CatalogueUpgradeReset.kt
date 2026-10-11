package eu.kanade.tachiyomi.data.extensionstore

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/** Installation-local state. The previous catalogue-only reset does not satisfy this migration. */
class CatalogueUpgradeReset(store: PreferenceStore) {
    private val phase = store.getInt(Preference.appStateKey("catalogue_upgrade_reset_v2"), UNINITIALIZED)

    val pending: Boolean get() = phase.get() != COMPLETE

    fun prepare(previousVersion: Int) {
        if (phase.get() == UNINITIALIZED) {
            phase.set(if (previousVersion == 0) COMPLETE else RESET_CATALOGUES)
        }
    }

    suspend fun resetCatalogues(clear: suspend () -> Unit) = lock.withLock {
        if (phase.get() == RESET_CATALOGUES) {
            withContext(NonCancellable) {
                clear()
                phase.set(REMOVE_EXTENSIONS)
            }
        }
    }

    suspend fun finish(extensionsRemain: suspend () -> Boolean): Boolean = lock.withLock {
        if (phase.get() == COMPLETE) return@withLock true
        if (phase.get() != REMOVE_EXTENSIONS || extensionsRemain()) return@withLock false
        phase.set(COMPLETE)
        true
    }

    private companion object {
        const val UNINITIALIZED = 0
        const val RESET_CATALOGUES = 1
        const val REMOVE_EXTENSIONS = 2
        const val COMPLETE = 3
        val lock = Mutex()
    }
}

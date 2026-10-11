package eu.kanade.tachiyomi.ui.search

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/** Installation-local education, initialized before the migration updates the previous version. */
class AtlasUpgradeNotice(store: PreferenceStore) {
    val state = store.getInt(Preference.appStateKey("atlas_upgrade_notice_v1"), 0)

    fun initialize(previousVersion: Int) {
        if (state.get() != 0) return
        state.set(if (previousVersion > 0) PENDING else FINISHED)
    }

    fun acknowledge() = state.set(FINISHED)

    companion object {
        const val PENDING = 1
        private const val FINISHED = 2
    }
}

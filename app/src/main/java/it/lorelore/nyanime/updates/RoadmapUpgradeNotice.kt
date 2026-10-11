package it.lorelore.nyanime.updates

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/** Installation-local, initialized before migration replaces the previous version. */
class RoadmapUpgradeNotice(store: PreferenceStore) {
    val state = store.getInt(Preference.appStateKey("roadmap_080_notice_v1"), 0)

    fun initialize(previousVersion: Int, beforeStableRelease: Boolean) {
        if (!beforeStableRelease) {
            state.set(FINISHED)
            return
        }
        if (state.get() != 0) return
        // Any previous revision counts: legacy revision numbers need not be below ours.
        // Mark fresh installs immediately, before onboarding or a portable backup restore.
        state.set(if (previousVersion > 0) PENDING else FINISHED)
    }

    fun acknowledge() = state.set(FINISHED)

    companion object {
        const val PENDING = 1
        private const val FINISHED = 2
    }
}

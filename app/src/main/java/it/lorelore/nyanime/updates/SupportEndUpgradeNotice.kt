package it.lorelore.nyanime.updates

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/** Decided before migration: only existing installations receive the final support notice. */
class SupportEndUpgradeNotice(store: PreferenceStore) {
    private val previousRoadmap = RoadmapUpgradeNotice(store)
    val state = store.getInt(Preference.appStateKey("support_end_notice_v1"), 0)

    fun initialize(previousVersion: Int) {
        // The old roadmap must not promise future releases, even when still pending.
        previousRoadmap.acknowledge()
        if (state.get() != 0) return
        // Legacy revision numbering is intentionally irrelevant to eligibility.
        // Mark fresh installs before onboarding or any manual backup restoration.
        state.set(if (previousVersion > 0) PENDING else FINISHED)
    }

    fun acknowledge() = state.set(FINISHED)

    companion object {
        const val PENDING = 1
        private const val FINISHED = 2
    }
}

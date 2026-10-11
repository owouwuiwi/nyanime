package eu.kanade.tachiyomi.data.discovery

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/** Local UI exclusions; history, library entries and episode progress are never deleted. */
class ResumeVisibility(store: PreferenceStore) {
    val hidden = store.getStringSet(Preference.appStateKey("discovery_hidden_resume"), emptySet())

    @Synchronized fun hide(animeId: Long) {
        hidden.set(hidden.get() + animeId.toString())
    }

    @Synchronized fun restore(animeId: Long) {
        hidden.set(hidden.get() - animeId.toString())
    }

    @Synchronized fun restoreAll() = hidden.set(emptySet())
}

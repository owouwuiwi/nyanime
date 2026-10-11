package eu.kanade.tachiyomi.data.addon

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/** An explanation acknowledged on this installation, independently of installed packages. */
class AddonIntroduction(store: PreferenceStore) {
    // The old store flag was consumed before display; it is not proof of acknowledgement.
    val complete = store.getBoolean(Preference.appStateKey("addon_introduction_complete"), false)

    fun confirm() = complete.set(true)
}

package eu.kanade.tachiyomi.ui.privacy

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

enum class PrivacyArea(val key: String) {
    VIDEO("video"),
    READER("reader"),
    DETAILS("details"),
    LIBRARY("library"),
    HISTORY("history"),
    RESUME("resume"),
    SEARCH("search"),
    NSFW("nsfw"),
}

class PrivacyDisplayPreferences(private val store: PreferenceStore) {
    fun enabled() = store.getBoolean("privacy_display_enabled", false)
    fun onlyInIncognito() = store.getBoolean("privacy_display_only_incognito", false)
    fun area(area: PrivacyArea) = store.getBoolean("privacy_display_area_${area.key}", area != PrivacyArea.NSFW)

    // Installation state is excluded by the existing backup preference policy.
    fun everApplied() = store.getBoolean(Preference.appStateKey("privacy_display_applied"), false)
    fun warningShown() = store.getBoolean(Preference.appStateKey("privacy_display_warning"), false)

    fun claimFailureWarning(): Boolean {
        if (!everApplied().get() || warningShown().get()) return false
        warningShown().set(true)
        return true
    }
}

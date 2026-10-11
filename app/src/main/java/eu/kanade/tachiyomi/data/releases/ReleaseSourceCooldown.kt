package eu.kanade.tachiyomi.data.releases

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** A rate-limited source pauses its other titles too; this survives process recreation. */
object ReleaseSourceCooldown {
    private fun preference() = Injekt.get<PreferenceStore>()
        .getStringSet(Preference.appStateKey("release_source_cooldowns"), emptySet())

    fun active(medium: ReleaseMedium, source: Long, now: Long): Boolean = preference().get().any {
        it.startsWith("${medium.name}|$source|") && (it.substringAfterLast('|').toLongOrNull() ?: 0) > now
    }

    @Synchronized
    fun defer(medium: ReleaseMedium, source: Long, now: Long) {
        val key = "${medium.name}|$source|"
        val pref = preference()
        pref.set(
            pref.get().filterTo(mutableSetOf()) {
                !it.startsWith(key) && (it.substringAfterLast('|').toLongOrNull() ?: 0) > now
            } +
                "$key${now + 15 * ReleasePolicy.MINUTE}",
        )
    }
}

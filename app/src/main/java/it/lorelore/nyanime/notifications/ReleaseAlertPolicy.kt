package it.lorelore.nyanime.notifications

import eu.kanade.tachiyomi.data.releases.FollowMode
import eu.kanade.tachiyomi.data.releases.ReleaseSubscription

/** Alert choices do not remove titles from the agenda, library or tracking. */
object ReleaseAlertPolicy {
    fun allows(
        subscription: ReleaseSubscription,
        explicitOnly: Boolean,
        reminder: Boolean,
        parent: ReleaseSubscription? = null,
    ): Boolean {
        if (subscription.mode == FollowMode.IGNORE) return false
        val inherited = parent?.takeIf { subscription.mode == FollowMode.AUTO }
        if (inherited?.mode == FollowMode.IGNORE) return false
        if (explicitOnly && subscription.mode != FollowMode.FOLLOW && inherited?.mode != FollowMode.FOLLOW) return false
        return (if (reminder) subscription.reminder else subscription.availability) &&
            (inherited == null || if (reminder) inherited.reminder else inherited.availability)
    }
}

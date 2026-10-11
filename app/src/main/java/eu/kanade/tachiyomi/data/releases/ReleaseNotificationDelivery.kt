package eu.kanade.tachiyomi.data.releases

internal object ReleaseNotificationDelivery {
    suspend fun deliver(
        allowed: () -> Boolean,
        post: suspend () -> Unit,
        acknowledge: suspend () -> Unit,
    ) {
        if (!allowed()) return
        post()
        // Permission/channel can change while fetching the artwork or building the notification.
        if (allowed()) acknowledge()
    }
}

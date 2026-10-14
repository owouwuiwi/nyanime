package eu.kanade.tachiyomi.data.track

import androidx.annotation.CallSuper
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

abstract class BaseTracker(
    override val id: Long,
    override val name: String,
) : Tracker {

    val trackPreferences: TrackPreferences by injectLazy()
    val networkService: NetworkHelper by injectLazy()

    override val client: OkHttpClient
        get() = networkService.client

    // Application and remote support for reading dates
    override val supportsReadingDates: Boolean = false

    override val supportsPrivateTracking: Boolean = false

    private val accountRefresh = TrackerAccountRefresh { getUsername().takeIf { isLoggedIn } }
    val isRefreshingAccount = accountRefresh.state
    open val supportsAccountRefresh: Boolean = false

    suspend fun refreshAccountSettings(): Boolean {
        if (!supportsAccountRefresh) return false
        return accountRefresh.execute(::loadAccountSettings, ::applyAccountSettings)
    }

    protected open suspend fun loadAccountSettings(account: String): TrackerAccountSettings =
        error("Account metadata refresh is unavailable")

    protected open fun applyAccountSettings(settings: TrackerAccountSettings) {
        trackPreferences.trackDisplayName(this).set(settings.displayName)
    }

    @CallSuper
    override fun logout() {
        accountRefresh.invalidate {
            trackPreferences.setCredentials(this, "", "")
            trackPreferences.trackDisplayName(this).delete()
        }
    }

    override val isLoggedIn: Boolean
        get() = getUsername().isNotEmpty() &&
            getPassword().isNotEmpty()

    override val isLoggedInFlow: Flow<Boolean> by lazy {
        combine(
            trackPreferences.trackUsername(this).changes(),
            trackPreferences.trackPassword(this).changes(),
        ) { username, password ->
            username.isNotEmpty() && password.isNotEmpty()
        }
    }

    override fun getUsername() = trackPreferences.trackUsername(this).get()

    override fun getPassword() = trackPreferences.trackPassword(this).get()

    override fun saveCredentials(username: String, password: String) {
        if (getUsername() != username) trackPreferences.trackDisplayName(this).delete()
        trackPreferences.setCredentials(this, username, password)
        if (username.isNotBlank() && password.isNotBlank()) {
            RetroactiveTracking.onTrackerLogin()
            LibraryAutoTracking.onForeground()
        }
    }
}

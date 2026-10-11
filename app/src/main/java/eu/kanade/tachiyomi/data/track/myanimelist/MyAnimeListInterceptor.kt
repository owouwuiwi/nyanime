package eu.kanade.tachiyomi.data.track.myanimelist

import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALOAuth
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.io.IOException

class MyAnimeListInterceptor(private val myanimelist: MyAnimeList) : Interceptor {

    private val json: Json by injectLazy()

    @Volatile
    private var oauth: MALOAuth? = myanimelist.loadOAuth()
    private val tokenExpired get() = myanimelist.getIfAuthExpired()

    override fun intercept(chain: Interceptor.Chain): Response {
        if (tokenExpired) {
            throw MALTokenExpired()
        }
        val originalRequest = chain.request()

        val currentAuth = if (oauth?.isExpired() == true) {
            refreshToken(chain)
        } else {
            oauth
        }
            ?: throw IOException("MAL: User is not authenticated")

        // Add the authorization header to the original request
        val authRequest = originalRequest.newBuilder()
            .header("Authorization", "Bearer ${currentAuth.accessToken}")
            // .header("User-Agent", "Aniyomi v${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})")
            .build()

        return chain.proceed(authRequest)
    }

    /**
     * Called when the user authenticates with MyAnimeList for the first time. Sets the refresh token
     * and the oauth object.
     */
    @Synchronized
    fun setAuth(oauth: MALOAuth?) {
        this.oauth = oauth
        myanimelist.saveOAuth(oauth)
    }

    private fun refreshToken(chain: Interceptor.Chain): MALOAuth = synchronized(this) {
        if (tokenExpired) throw MALTokenExpired()
        oauth?.takeUnless { it.isExpired() }?.let { return@synchronized it }

        val response = try {
            chain.proceed(MyAnimeListApi.refreshTokenRequest(oauth!!))
        } catch (_: Throwable) {
            throw MALTokenRefreshFailed()
        }

        if (response.code == 401) {
            response.close()
            myanimelist.setAuthExpired()
            throw MALTokenExpired()
        }

        return runCatching {
            if (response.isSuccessful) {
                with(json) { response.parseAs<MALOAuth>() }
            } else {
                response.close()
                null
            }
        }
            .getOrNull()
            ?.also {
                this.oauth = it
                myanimelist.saveOAuth(it)
            }
            ?: throw MALTokenRefreshFailed()
    }
}

class MALTokenRefreshFailed : IOException("MAL: Failed to refresh account token")
class MALTokenExpired : IOException("MAL: Login has expired")

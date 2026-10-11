package eu.kanade.tachiyomi.data.track.kitsu

import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.track.TrackerTokenSession
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuOAuth
import eu.kanade.tachiyomi.data.track.kitsu.dto.isExpired
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.io.IOException

class KitsuInterceptor(private val kitsu: Kitsu) : Interceptor {

    private val json: Json by injectLazy()

    /**
     * OAuth object used for authenticated requests.
     */
    private val session = TrackerTokenSession(kitsu.restoreToken(), kitsu::saveToken)

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val currAuth = session.valid(KitsuOAuth::isExpired) { current ->
            val refreshToken = current.refreshToken ?: throw IOException("Account login has expired")
            chain.proceed(KitsuApi.refreshTokenRequest(refreshToken)).use { response ->
                if (!response.isSuccessful) throw IOException("Account renewal failed (HTTP ${response.code})")
                json.decodeFromString<KitsuOAuth>(response.body.string())
            }
        }

        // Add the authorization header to the original request.
        val authRequest = originalRequest.newBuilder()
            .header("Authorization", "Bearer ${currAuth.accessToken}")
            .header("User-Agent", "Aniyomi v${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})")
            .header("Accept", "application/vnd.api+json")
            .header("Content-Type", "application/vnd.api+json")
            .build()

        return chain.proceed(authRequest)
    }

    fun newAuth(oauth: KitsuOAuth?) {
        session.set(oauth)
    }
}

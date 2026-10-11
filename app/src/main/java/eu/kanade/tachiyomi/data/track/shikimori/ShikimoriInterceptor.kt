package eu.kanade.tachiyomi.data.track.shikimori

import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.track.TrackerTokenSession
import eu.kanade.tachiyomi.data.track.shikimori.dto.SMOAuth
import eu.kanade.tachiyomi.data.track.shikimori.dto.isExpired
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.io.IOException

class ShikimoriInterceptor(private val shikimori: Shikimori) : Interceptor {

    private val json: Json by injectLazy()

    /**
     * OAuth object used for authenticated requests.
     */
    private val session = TrackerTokenSession(shikimori.restoreToken(), shikimori::saveToken)

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val currAuth = session.valid(SMOAuth::isExpired) { current ->
            val refreshToken = current.refreshToken ?: throw IOException("Account login has expired")
            chain.proceed(ShikimoriApi.refreshTokenRequest(refreshToken)).use { response ->
                if (!response.isSuccessful) throw IOException("Account renewal failed (HTTP ${response.code})")
                json.decodeFromString<SMOAuth>(response.body.string())
            }
        }
        // Add the authorization header to the original request.
        val authRequest = originalRequest.newBuilder()
            .header("Authorization", "Bearer ${currAuth.accessToken}")
            .header("User-Agent", "Aniyomi v${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})")
            .build()

        return chain.proceed(authRequest)
    }

    fun newAuth(oauth: SMOAuth?) {
        session.set(oauth)
    }
}

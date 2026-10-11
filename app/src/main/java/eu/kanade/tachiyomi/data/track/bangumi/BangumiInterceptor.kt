package eu.kanade.tachiyomi.data.track.bangumi

import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.track.TrackerTokenSession
import eu.kanade.tachiyomi.data.track.bangumi.dto.BGMOAuth
import eu.kanade.tachiyomi.data.track.bangumi.dto.isExpired
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.io.IOException

class BangumiInterceptor(private val bangumi: Bangumi) : Interceptor {

    private val json: Json by injectLazy()

    /**
     * OAuth object used for authenticated requests.
     */
    private val session = TrackerTokenSession(bangumi.restoreToken(), bangumi::saveToken)

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val currAuth = session.valid(BGMOAuth::isExpired) { current ->
            val refreshToken = current.refreshToken ?: throw IOException("Account login has expired")
            chain.proceed(BangumiApi.refreshTokenRequest(refreshToken)).use { response ->
                if (!response.isSuccessful) throw IOException("Account renewal failed (HTTP ${response.code})")
                json.decodeFromString<BGMOAuth>(response.body.string()).let {
                    it.copy(userId = it.userId ?: current.userId)
                }
            }
        }

        return originalRequest.newBuilder()
            .header(
                "User-Agent",
                "jmir1/Aniyomi/v${BuildConfig.VERSION_NAME} (Android) (https://github.com/aniyomiorg/aniyomi)",
            )
            .apply {
                header("Authorization", "Bearer ${currAuth.accessToken}")
            }
            .build()
            .let(chain::proceed)
    }

    fun newAuth(oauth: BGMOAuth?) {
        session.set(oauth)
    }
}

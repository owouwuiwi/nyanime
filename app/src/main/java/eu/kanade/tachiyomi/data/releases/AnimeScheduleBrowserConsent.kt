package eu.kanade.tachiyomi.data.releases

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Ask the site's consent endpoint to generate its own necessary-only cookie, before opening login. */
internal class AnimeScheduleBrowserConsent(
    api: OkHttpClient = Injekt.get<NetworkHelper>().apiClient,
    private val origin: HttpUrl = ORIGIN.toHttpUrl(),
) {
    private val client = AnimeScheduleHttpClient.create(api)
    suspend fun necessaryCookie(): String? {
        val request = Request.Builder().url(origin.resolve("/api/v3/consent")!!)
            .header("Origin", origin.toString().removeSuffix("/"))
            .header("Referer", origin.resolve("/login").toString())
            .post("""{"analytical":false}""".toRequestBody("application/json".toMediaType())).build()
        return client.newCall(request).await().use { response ->
            if (!response.isSuccessful) return@use null
            response.headers.values("Set-Cookie").firstNotNullOfOrNull { header ->
                Cookie.parse(origin, header)?.takeIf {
                    it.name == COOKIE_NAME && it.domain == origin.host && it.path == "/" && it.value.isNotEmpty()
                }?.let { header }
            }
        }
    }

    companion object {
        const val ORIGIN = "https://animeschedule.net"
        private const val COOKIE_NAME = "as_consent_v3"
        fun alreadyAccepted(cookies: String?): Boolean = cookies.orEmpty().split(';').any {
            val parts = it.trim().split('=', limit = 2)
            parts.size == 2 && parts[0] == COOKIE_NAME && parts[1].isNotEmpty()
        }
    }
}

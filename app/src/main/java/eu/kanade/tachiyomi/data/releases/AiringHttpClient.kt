package eu.kanade.tachiyomi.data.releases

import eu.kanade.tachiyomi.data.track.anilist.AnilistRequestLimiter
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

internal object AiringHttpClient {
    fun create(apiClient: OkHttpClient): OkHttpClient = apiClient.newBuilder()
        .addInterceptor(AnilistRequestLimiter)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        // The shared budget can delay a call before it reaches the network.
        .callTimeout(45, TimeUnit.SECONDS)
        .build()
}

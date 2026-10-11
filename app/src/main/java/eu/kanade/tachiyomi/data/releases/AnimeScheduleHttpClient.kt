package eu.kanade.tachiyomi.data.releases

import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

internal object AnimeScheduleHttpClient {
    fun create(api: OkHttpClient): OkHttpClient = api.newBuilder().apply {
        interceptors().removeAll { it is HttpLoggingInterceptor }
        networkInterceptors().removeAll { it is HttpLoggingInterceptor }
    }.cookieJar(CookieJar.NO_COOKIES).cache(null).followRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()
}

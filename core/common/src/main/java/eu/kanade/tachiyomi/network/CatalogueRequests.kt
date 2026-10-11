package eu.kanade.tachiyomi.network

import okhttp3.CacheControl
import okhttp3.Request
import java.util.concurrent.TimeUnit.SECONDS

private val CATALOGUE_CACHE_CONTROL = CacheControl.Builder().maxAge(0, SECONDS).build()

/** Mutable catalogue indexes must be revalidated, even while their HTTP cache entry is fresh. */
fun catalogueRequest(url: String): Request = GET(url, cache = CATALOGUE_CACHE_CONTROL)

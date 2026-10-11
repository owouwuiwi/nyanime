package tachiyomi.data.release

import eu.kanade.tachiyomi.network.HttpException
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.release.model.Release
import java.io.IOException

/** Prefer the current publisher; consult former addresses only when it cannot be reached. */
object ReleaseRepositoryFallback {
    suspend fun latest(
        primary: String,
        fallbacks: List<String>,
        load: suspend (String) -> Release?,
    ): Release? {
        val repositories = (listOf(primary) + fallbacks).distinct()
        for ((index, repository) in repositories.withIndex()) {
            try {
                // A successful response, including no compatible release, is authoritative.
                return load(repository)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                // Authentication and rate limits affect both addresses: don't retry those.
                if (index == repositories.lastIndex || e.code !in retryableCodes) throw e
            } catch (e: IOException) {
                if (index == repositories.lastIndex) throw e
            }
        }
        return null
    }

    private val retryableCodes = setOf(404, 410, 500, 502, 503, 504)
}

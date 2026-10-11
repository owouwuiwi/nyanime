package eu.kanade.tachiyomi.data.tv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import nyanime.tv.api.TvCatalogueRequest
import nyanime.tv.api.TvChannel
import java.text.Normalizer
import java.util.Locale

data class TvSearchPage(
    val sourceId: Long,
    val channels: List<TvChannel> = emptyList(),
    val loading: Boolean = false,
    val saved: Boolean = false,
    val error: Boolean = false,
    val nextCursor: String? = null,
)

/** One query lane consumed with the existing Atlante collectLatest/generation cancellation. */
class TvSearchSession(
    private val repository: TvRepository,
    private val sharedRequests: Semaphore,
    private val work: CoroutineDispatcher = Dispatchers.Default,
) {
    /** Empty exploration never contacts a source. Nonempty queries publish each source independently. */
    fun search(
        query: String,
        requests: Map<Long, TvCatalogueRequest>,
        includeAdult: Boolean,
    ): Flow<Map<Long, TvSearchPage>> = channelFlow {
        require(query.length <= 512 && query.none(Char::isISOControl) && requests.size <= 128)
        val wanted = normalize(query.trim()).split(' ').filter { it.isNotEmpty() }
        var pages = emptyMap<Long, TvSearchPage>()
        val publication = Mutex()
        send(emptyMap())
        suspend fun publishPage(page: TvSearchPage) {
            publication.withLock {
                pages = pages + (page.sourceId to page)
                send(pages)
            }
        }
        for ((sourceId, request) in requests) {
            launch {
                suspend fun publish(result: TvResult<nyanime.tv.api.TvCatalogue>) {
                    val channels = withContext(work) {
                        result.data?.channels.orEmpty().filter { channel ->
                            (includeAdult || !channel.adult) &&
                                (
                                    wanted.isEmpty() ||
                                        normalize("${channel.name} ${channel.feedName.orEmpty()}").let { title ->
                                            wanted.all(title::contains)
                                        }
                                    )
                        }.distinctBy { it.ref }
                    }
                    val page =
                        TvSearchPage(
                            sourceId,
                            channels,
                            result.loading,
                            result.saved,
                            result.error,
                            result.data?.nextCursor,
                        )
                    publishPage(page)
                }
                try {
                    if (wanted.isEmpty()) {
                        publish(repository.cachedCatalogue(sourceId, request))
                    } else {
                        sharedRequests.withPermit {
                            repository.catalogue(sourceId, request).collect { publish(it) }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A bad or unavailable extension cannot erase the other sources' results.
                    publication.withLock {
                        val previous = pages[sourceId]
                        val next = previous?.copy(loading = false, error = true) ?: TvSearchPage(sourceId, error = true)
                        pages = pages + (sourceId to next)
                        send(pages)
                    }
                }
            }
        }
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
}

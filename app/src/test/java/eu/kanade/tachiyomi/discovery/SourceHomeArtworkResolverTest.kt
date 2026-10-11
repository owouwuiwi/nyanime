package eu.kanade.tachiyomi.discovery

import eu.kanade.tachiyomi.data.discovery.SourceHomeArtworkResolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.entries.anime.model.Anime
import java.util.concurrent.atomic.AtomicInteger

class SourceHomeArtworkResolverTest {
    private val anime = Anime.create().copy(id = 1, source = 7, url = "/series/one", title = "Test title")
    private val artwork = SourceHomeArtworkResolver.Artwork("https://images.test/cover.jpg", null)

    @Test fun simultaneousCardsForTheSameTitleReuseOneExtensionRequest() = runBlocking {
        val calls = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val resolver = SourceHomeArtworkResolver(fetch = {
            calls.incrementAndGet()
            started.complete(Unit)
            release.await()
            artwork
        })
        withTimeout(5_000) {
            val results = List(4) { async { resolver.resolve(anime) } }
            started.await()
            release.complete(Unit)
            assertEquals(List(4) { artwork }, results.awaitAll())
        }
        assertEquals(1, calls.get())
    }

    @Test fun recoveryLimitsConcurrentNetworkWorkToTwoRequests() = runBlocking {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val twoStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val resolver = SourceHomeArtworkResolver(fetch = {
            val current = active.incrementAndGet()
            maximum.updateAndGet { previous -> maxOf(previous, current) }
            if (current == 2) twoStarted.complete(Unit)
            try {
                release.await()
                artwork
            } finally {
                active.decrementAndGet()
            }
        })
        withTimeout(5_000) {
            val results = List(8) { index -> async { resolver.resolve(anime.copy(url = "/series/$index")) } }
            twoStarted.await()
            release.complete(Unit)
            results.awaitAll()
        }
        assertEquals(2, maximum.get())
        assertEquals(0, active.get())
    }

    @Test fun leavingHomeCancelsRecoveryAndDoesNotPoisonTheNextAttempt() = runBlocking {
        val calls = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val resolver = SourceHomeArtworkResolver(fetch = {
            if (calls.incrementAndGet() == 1) {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
            artwork
        })
        withTimeout(5_000) {
            val job = launch { resolver.resolve(anime) }
            started.await()
            job.cancelAndJoin()
            cancelled.await()
            assertEquals(artwork, resolver.resolve(anime))
        }
        assertEquals(2, calls.get())
    }

    @Test fun cacheSeparatesSourcesExpiresMissingArtworkAndAllowsManualRecovery() = runBlocking {
        var clock = 0L
        var calls = 0
        var result = SourceHomeArtworkResolver.Artwork(null, null)
        val resolver = SourceHomeArtworkResolver(fetch = {
            calls++
            result
        }, now = { clock })
        resolver.resolve(anime)
        resolver.resolve(anime)
        assertEquals(1, calls)
        clock = 60_001
        result = artwork
        assertEquals(artwork, resolver.resolve(anime))
        resolver.resolve(anime.copy(source = 8))
        resolver.resolve(anime, refresh = true)
        assertEquals(4, calls)
    }
}

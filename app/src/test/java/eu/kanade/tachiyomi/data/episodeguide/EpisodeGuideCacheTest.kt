package eu.kanade.tachiyomi.data.episodeguide

import eu.kanade.tachiyomi.data.workinfo.WorkCacheEntry
import eu.kanade.tachiyomi.data.workinfo.WorkInfoCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EpisodeGuideCacheTest {
    private class Cache : WorkInfoCache {
        val values = mutableMapOf<String, WorkCacheEntry>()
        override suspend fun read(key: String) = values[key]
        override suspend fun write(key: String, entry: WorkCacheEntry) {
            values[key] = entry
        }
    }

    @Test fun `concurrent readers share a request and one cancellation leaves the other alive`() = runTest {
        val disk = Cache()
        val cache = EpisodeGuideCache(disk, backgroundScope, Json) { 1000 }
        val ready = CompletableDeferred<Unit>()
        var calls = 0
        val fetch: suspend (String) -> String = {
            calls++
            ready.await()
            "{}"
        }
        val first = async { cache.load("guide:1", true, true, fetch) }
        val second = async { cache.load("guide:1", true, true, fetch) }
        runCurrent()
        assertEquals(1, calls)
        first.cancelAndJoin()
        ready.complete(Unit)
        assertEquals("{}", second.await())
        assertNotNull(disk.values["guide:1"])
    }

    @Test fun `last reader cancellation stops the request and does not save an incomplete result`() = runTest {
        val disk = Cache()
        val cache = EpisodeGuideCache(disk, backgroundScope, Json)
        var stopped = false
        val request =
            async {
                cache.load("guide:1", true, true) {
                    try {
                        awaitCancellation()
                    } finally {
                        stopped = true
                    }
                }
            }
        runCurrent()
        request.cancelAndJoin()
        runCurrent()
        assertTrue(stopped)
        assertTrue(disk.values.isEmpty())
    }

    @Test fun `offline uses stale cache and a failed refresh retains the saved data`() = runTest {
        val disk = Cache()
        disk.values["guide:1"] = WorkCacheEntry(0, """{"saved":true}""")
        val cache = EpisodeGuideCache(disk, backgroundScope, Json) { 100_000_000 }
        assertEquals("""{"saved":true}""", cache.load("guide:1", false, true) { error("Must not use network") })
        assertEquals(
            """{"saved":true}""",
            cache.load("guide:1", true, true) {
                throw java.io.IOException("Unavailable")
            },
        )
        assertEquals(0L, disk.values["guide:1"]!!.savedAt)
    }

    @Test fun `private requests are isolated and leaving incognito drops late results`() = runTest {
        val disk = Cache()
        val cache = EpisodeGuideCache(disk, backgroundScope, Json) { 1000 }
        val ready = CompletableDeferred<Unit>()
        var calls = 0
        val privateRequest =
            async {
                cache.load("same-guide", true, false) {
                    calls++
                    ready.await()
                    """{"private":true}"""
                }
            }
        val normal = async {
            cache.load("same-guide", true, true) {
                calls++
                "{}"
            }
        }
        runCurrent()
        assertEquals(2, calls)
        assertEquals("{}", normal.await())
        cache.clearPrivate()
        ready.complete(Unit)
        privateRequest.await()
        assertEquals("{}", cache.saved("same-guide", false)!!.payload)
        assertEquals("{}", disk.values["same-guide"]!!.payload)
    }

    @Test fun `fresh data prevents network use and malformed replies are never written`() = runTest {
        val disk = Cache()
        disk.values["guide:1"] = WorkCacheEntry(100, "{}")
        val cache = EpisodeGuideCache(disk, backgroundScope, Json) { 100 }
        assertEquals("{}", cache.load("guide:1", true, true) { error("Unexpected network") })
        try {
            cache.load("guide:2", true, true) { "not JSON" }
            fail<Unit>("Expected validation failure")
        } catch (
            _: kotlinx.serialization.SerializationException,
        ) {}
        assertNull(disk.values["guide:2"])
    }
}

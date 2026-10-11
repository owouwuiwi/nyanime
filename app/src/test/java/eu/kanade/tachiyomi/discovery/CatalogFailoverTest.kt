package eu.kanade.tachiyomi.discovery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.discovery.AnimeCatalogRemote
import tachiyomi.domain.discovery.CatalogAnime
import tachiyomi.domain.discovery.CatalogFeed
import tachiyomi.domain.discovery.CatalogId
import tachiyomi.domain.discovery.CatalogIdentityMatcher
import tachiyomi.domain.discovery.CatalogPage
import tachiyomi.domain.discovery.CatalogRequest
import tachiyomi.domain.discovery.FailoverAnimeCatalogRemote
import java.io.IOException

class CatalogFailoverTest {
    private class Remote(val provider: String, var error: Exception? = null) : AnimeCatalogRemote {
        var calls = 0
        override suspend fun fetch(request: CatalogRequest): CatalogPage {
            calls++
            error?.let { throw it }
            return CatalogPage(listOf(CatalogAnime(CatalogId(provider, 7), provider)), true, provider)
        }
        override suspend fun details(id: CatalogId): CatalogAnime {
            calls++
            error?.let { throw it }
            return CatalogAnime(id, provider)
        }
    }

    @Test
    fun `unavailable primary switches automatically to independent catalogue`() = runBlocking {
        val primary = Remote("anilist", IOException("Unavailable"))
        val fallback = Remote("kitsu")
        val result = FailoverAnimeCatalogRemote(primary, fallback).fetch(CatalogRequest(CatalogFeed.TOP))
        assertEquals("kitsu", result.provider)
        assertTrue(result.notice.orEmpty().contains("Kitsu"))
        assertEquals(1, fallback.calls)
    }

    @Test
    fun `successful primary never queries fallback`() = runBlocking {
        val fallback = Remote("kitsu")
        FailoverAnimeCatalogRemote(Remote("anilist"), fallback).fetch(CatalogRequest(CatalogFeed.TOP))
        assertEquals(0, fallback.calls)
    }

    @Test
    fun `pagination stays on fallback after primary recovers`() = runBlocking {
        val primary = Remote("anilist")
        val router = FailoverAnimeCatalogRemote(primary, Remote("kitsu"))
        val result = router.fetch(CatalogRequest(CatalogFeed.TOP, page = 2, provider = "kitsu"))
        assertEquals("kitsu", result.provider)
        assertEquals(0, primary.calls)
        assertNotEquals(
            CatalogRequest(CatalogFeed.TOP, page = 2).cacheKey,
            CatalogRequest(CatalogFeed.TOP, page = 2, provider = "kitsu").cacheKey,
        )
    }

    @Test
    fun `explicit primary page does not mix fallback results`() {
        val fallback = Remote("kitsu")
        val router = FailoverAnimeCatalogRemote(Remote("anilist", IOException()), fallback)
        assertThrows(IOException::class.java) {
            runBlocking { router.fetch(CatalogRequest(CatalogFeed.TOP, page = 2, provider = "anilist")) }
        }
        assertEquals(0, fallback.calls)
    }

    @Test
    fun `cancellation does not start a fallback request`() {
        val fallback = Remote("kitsu")
        val router = FailoverAnimeCatalogRemote(Remote("anilist", CancellationException()), fallback)
        assertThrows(CancellationException::class.java) {
            runBlocking { router.fetch(CatalogRequest(CatalogFeed.TOP)) }
        }
        assertEquals(0, fallback.calls)
    }

    @Test
    fun `equal numeric IDs are distinct for details and tracking`() = runBlocking {
        val router = FailoverAnimeCatalogRemote(Remote("anilist"), Remote("kitsu"))
        val anime = router.details(CatalogId("kitsu", 7))
        assertEquals("kitsu", anime.title)
        assertFalse(CatalogIdentityMatcher.matches(anime, 2, 7))
        assertTrue(CatalogIdentityMatcher.matches(anime, 3, 7))
        assertFalse(CatalogIdentityMatcher.matches(anime, 1, 7))
        assertTrue(CatalogIdentityMatcher.matches(anime.copy(malId = 42), 1, 42))
    }
}

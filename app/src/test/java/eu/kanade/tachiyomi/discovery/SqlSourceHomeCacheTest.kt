package eu.kanade.tachiyomi.discovery

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.discovery.DiscoveryDatabase
import tachiyomi.data.discovery.SqlSourceHomeCache
import tachiyomi.domain.discovery.SourceHomeCacheEntry
import tachiyomi.domain.discovery.SourceHomeCacheKey
import tachiyomi.domain.discovery.SourceHomePage
import tachiyomi.domain.discovery.SourceHomePresentation
import tachiyomi.domain.discovery.homePresentation
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.repository.AnimeRepository

class SqlSourceHomeCacheTest {
    @Test fun repeatedSeriesCardsRetainTheirOwnMetadataAfterRestart() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            DiscoveryDatabase.Schema.create(driver)
            val cache = SqlSourceHomeCache(DiscoveryDatabase(driver), entries)
            val cards = listOf("Ep 9", "Ep 8").map { episode ->
                anime.copy(memo = SourceHomePresentation(id = episode, badges = listOf(episode)).attachTo(anime.memo))
            }
            cache.write(key, page.copy(page = SourceHomePage(cards, false, "This season")))
            coEvery { entries.getAnimeById(10) } returns
                anime.copy(favorite = true, title = "Updated", episodeFlags = 42)
            val restored = cache.read(key)!!.page
            assertEquals(listOf("Ep 9", "Ep 8"), restored.items.map { it.homePresentation!!.badges.single() })
            assertTrue(
                restored.items.all {
                    it.id == 10L &&
                        it.favorite &&
                        it.episodeFlags == 42L &&
                        it.title == "Updated"
                },
            )
            assertEquals("This season", restored.title)
        }
    }

    private val key = SourceHomeCacheKey("producer", 42, "1", "popular", 1)
    private val anime = Anime.create().copy(id = 10, source = 42, title = "Example")
    private val entries = mockk<AnimeRepository>()
    private val page = SourceHomeCacheEntry(SourceHomePage(listOf(anime), true), 1000)

    @Test fun homeArtworkSurvivesRestartWithoutRestoringOldLibraryFlags() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            DiscoveryDatabase.Schema.create(driver)
            val cache = SqlSourceHomeCache(DiscoveryDatabase(driver), entries)
            val featured = anime.copy(
                thumbnailUrl = "https://example.org/current-cover.jpg",
                backgroundUrl = "https://example.org/banner.jpg",
                description = "Source synopsis",
            )
            cache.write(key, page.copy(page = SourceHomePage(listOf(featured), false)))
            coEvery { entries.getAnimeById(10) } returns anime.copy(
                favorite = true,
                title = "Local title",
                thumbnailUrl = "https://old.example.org/stale-cover.jpg",
                coverLastModified = 123,
            )
            val restored = cache.read(key)!!.page.items.single()
            assertEquals(featured.thumbnailUrl, restored.thumbnailUrl)
            assertEquals(123L, restored.coverLastModified)
            assertEquals(featured.backgroundUrl, restored.backgroundUrl)
            assertEquals(featured.description, restored.description)
            assertEquals("Local title", restored.title)
            assertEquals(true, restored.favorite)
        }
    }

    @Test fun oldHomeSnapshotsAreRefetchedInsteadOfRestoringDiscardedPosters() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            DiscoveryDatabase.Schema.create(driver)
            val database = DiscoveryDatabase(driver)
            val cache = SqlSourceHomeCache(database, entries)
            database.sourceHomeCacheQueries.put(
                "producer",
                42,
                "1",
                "popular",
                1,
                """{"ids":[10],"hasNextPage":false,"backgrounds":{"10":"https://example.org/banner.jpg"}}""",
                1000,
            )
            coEvery { entries.getAnimeById(10) } returns anime
            assertNull(cache.read(key))
            // A newly cached section with genuinely missing posters is still a valid snapshot.
            cache.write(key, page)
            assertEquals(page, cache.read(key))
        }
    }

    @Test
    fun migrationPreservesExistingCatalogAndSourceLinks() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            driver.execute(
                null,
                "CREATE TABLE catalog_cache (cache_key TEXT PRIMARY KEY, payload TEXT NOT NULL, " +
                    "fetched_at INTEGER NOT NULL, is_detail INTEGER NOT NULL)",
                0,
            )
            driver.execute(
                null,
                "CREATE TABLE source_links (provider TEXT NOT NULL, catalog_id INTEGER NOT NULL, " +
                    "source_id INTEGER NOT NULL, source_url TEXT NOT NULL, PRIMARY KEY(provider, catalog_id))",
                0,
            )
            val database = DiscoveryDatabase(driver)
            database.discoveryQueries.putCache("existing", "payload", 500, 1)
            database.discoveryQueries.putLink("kitsu", 7, 42, "/series")
            DiscoveryDatabase.Schema.migrate(driver, 1, DiscoveryDatabase.Schema.version)
            assertEquals("payload", database.discoveryQueries.findCache("existing").executeAsOne().payload)
            assertEquals("/series", database.discoveryQueries.findLink("kitsu", 7).executeAsOne().source_url)
            assertNull(database.sourceHomeCacheQueries.find("producer", 42, "1", "popular", 1).executeAsOneOrNull())
        }
    }

    @Test
    fun roundTripRehydratesCurrentLibraryStateRatherThanOldFlags() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            DiscoveryDatabase.Schema.create(driver)
            val cache = SqlSourceHomeCache(DiscoveryDatabase(driver), entries)
            cache.write(key, page)
            coEvery { entries.getAnimeById(10) } returns anime.copy(favorite = true, title = "Updated")
            val restored = cache.read(key)!!
            assertEquals("Updated", restored.page.items.single().title)
            assertEquals(true, restored.page.items.single().favorite)
            assertEquals(page.fetchedAt, restored.fetchedAt)
            assertNull(cache.read(key.copy(revision = "2")))
            assertNull(cache.read(key.copy(source = 43)))
        }
    }

    @Test
    fun missingEntriesForeignSourcesAndCorruptPayloadsAreCacheMisses() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            DiscoveryDatabase.Schema.create(driver)
            val database = DiscoveryDatabase(driver)
            val cache = SqlSourceHomeCache(database, entries)
            cache.write(key, page)
            coEvery { entries.getAnimeById(10) } throws IllegalStateException("removed")
            assertNull(cache.read(key))
            coEvery { entries.getAnimeById(10) } returns anime.copy(source = 999)
            assertNull(cache.read(key))
            database.sourceHomeCacheQueries.put("producer", 42, "1", "popular", 1, "bad json", 1000)
            assertNull(cache.read(key))
        }
    }

    @Test
    fun storedPagesAreBoundedAndExpiredRowsRemoved() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            DiscoveryDatabase.Schema.create(driver)
            val cache = SqlSourceHomeCache(DiscoveryDatabase(driver), entries)
            coEvery { entries.getAnimeById(10) } returns anime
            for (number in 1..129) cache.write(key.copy(page = number), page.copy(fetchedAt = number.toLong()))
            assertNull(cache.read(key))
            assertEquals(129L, cache.read(key.copy(page = 129))!!.fetchedAt)
            cache.write(key, page.copy(fetchedAt = 24 * 60 * 60_000L + 130))
            assertNull(cache.read(key.copy(page = 129)))
        }
    }
}

package eu.kanade.tachiyomi.data.library

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import data.History
import data.Mangas
import dataanime.Animehistory
import dataanime.Animes
import dataanime.Episodes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.data.AnimeUpdateStrategyColumnAdapter
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.FetchTypeColumnAdapter
import tachiyomi.data.MangaUpdateStrategyColumnAdapter
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.handlers.anime.AndroidAnimeDatabaseHandler
import tachiyomi.data.handlers.manga.AndroidMangaDatabaseHandler
import tachiyomi.mi.data.AnimeDatabase
import java.nio.file.Files

class DatabaseConcurrencyTest {
    @Test
    fun `nested writers and concurrent readers preserve both databases`() = runBlocking {
        exerciseBoth { driver, transaction, read ->
            coroutineScope {
                val writes = (1..40).map {
                    async(Dispatchers.Default) {
                        transaction {
                            val value = read()
                            delay(1)
                            transaction { driver.execute(null, "UPDATE test_progress SET value=${value + 1}", 0) }
                        }
                    }
                }
                val readers = (1..20).map { async(Dispatchers.Default) { read() } }
                writes.awaitAll()
                readers.awaitAll()
            }
            assertEquals(40L, read())
        }
    }

    @Test
    fun `cancelled writer rolls back and releases its only slot for later writes`() = runBlocking {
        exerciseBoth { driver, transaction, read ->
            coroutineScope {
                val started = CompletableDeferred<Unit>()
                val writer = launch {
                    transaction {
                        driver.execute(null, "UPDATE test_progress SET value=999", 0)
                        started.complete(Unit)
                        awaitCancellation()
                    }
                }
                started.await()
                assertEquals(0L, read())
                writer.cancelAndJoin()
                transaction { driver.execute(null, "UPDATE test_progress SET value=1", 0) }
                assertEquals(1L, read())
            }
        }
    }

    private suspend fun exerciseBoth(
        block: suspend (JdbcSqliteDriver, suspend (suspend () -> Unit) -> Unit, suspend () -> Long) -> Unit,
    ) {
        listOf(false, true).forEach { anime ->
            val file = Files.createTempFile("database-concurrency-", ".db").toFile()
            try {
                JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
                    driver.execute(null, "PRAGMA journal_mode=WAL", 0)
                    driver.execute(null, "PRAGMA busy_timeout=3000", 0)
                    driver.execute(null, "CREATE TABLE test_progress (value INTEGER NOT NULL)", 0)
                    driver.execute(null, "INSERT INTO test_progress VALUES (0)", 0)
                    fun scalar(): Long = driver.executeQuery(null, "SELECT value FROM test_progress", { cursor ->
                        cursor.next()
                        QueryResult.Value(cursor.getLong(0)!!)
                    }, 0).value
                    val manga = if (anime) {
                        null
                    } else {
                        AndroidMangaDatabaseHandler(
                            Database(
                                driver,
                                History.Adapter(DateColumnAdapter),
                                Mangas.Adapter(StringListColumnAdapter, MangaUpdateStrategyColumnAdapter),
                            ),
                            driver,
                        )
                    }
                    val video = if (!anime) {
                        null
                    } else {
                        AndroidAnimeDatabaseHandler(
                            AnimeDatabase(
                                driver,
                                Animehistory.Adapter(DateColumnAdapter),
                                Animes.Adapter(
                                    StringListColumnAdapter,
                                    AnimeUpdateStrategyColumnAdapter,
                                    FetchTypeColumnAdapter,
                                    MemoColumnAdapter,
                                ),
                                Episodes.Adapter(MemoColumnAdapter),
                            ),
                            driver,
                        )
                    }
                    withTimeout(15_000) {
                        if (manga != null) {
                            block(driver, { manga.await(true) { it() } }, { manga.await { scalar() } })
                        } else {
                            block(driver, { video!!.await(true) { it() } }, { video!!.await { scalar() } })
                        }
                    }
                }
                // Reopen a copy after the connection closes: committed data must survive checkpoint/restart.
                val copy = Files.createTempFile("database-copy-", ".db").toFile()
                try {
                    file.copyTo(copy, overwrite = true)
                    JdbcSqliteDriver("jdbc:sqlite:${copy.absolutePath}").use { driver ->
                        val valid = driver.executeQuery(null, "PRAGMA integrity_check", { cursor ->
                            cursor.next()
                            QueryResult.Value(cursor.getString(0))
                        }, 0).value
                        assertEquals("ok", valid)
                    }
                } finally {
                    copy.delete()
                }
            } finally {
                file.delete()
            }
        }
    }
}

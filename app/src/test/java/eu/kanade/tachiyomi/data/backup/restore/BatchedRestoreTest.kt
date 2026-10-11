package eu.kanade.tachiyomi.data.backup.restore

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import data.History
import data.Mangas
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.MangaUpdateStrategyColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.handlers.manga.AndroidMangaDatabaseHandler

class BatchedRestoreTest {
    @Test
    fun `nested suspending database writes roll back before retrying individual entries`() = runTest {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            val db = Database(
                driver,
                History.Adapter(DateColumnAdapter),
                Mangas.Adapter(StringListColumnAdapter, MangaUpdateStrategyColumnAdapter),
            )
            driver.execute(null, "CREATE TABLE restored (id INTEGER PRIMARY KEY CHECK (id <> 2))", 0)
            val handler = AndroidMangaDatabaseHandler(db, driver, Dispatchers.IO)
            suspend fun insert(id: Int) = handler.await(inTransaction = true) {
                driver.execute(null, "INSERT INTO restored VALUES (?)", 1) { bindLong(0, id.toLong()) }
                Unit
            }
            val failed = mutableListOf<Int>()
            restoreInBatches(
                entries = (1..6).toList(),
                batchSize = 3,
                restoreBatch = { chunk -> handler.await(true) { chunk.forEach { insert(it) } } },
                restoreEntry = { insert(it) },
                onError = { entry, _ -> failed += entry },
                onBatchComplete = {},
            )
            val rows = driver.executeQuery(null, "SELECT id FROM restored ORDER BY id", { cursor ->
                val ids = mutableListOf<Long>()
                while (cursor.next().value) ids += cursor.getLong(0)!!
                QueryResult.Value(ids)
            }, 0).value
            assertEquals(listOf(1L, 3L, 4L, 5L, 6L), rows)
            assertEquals(listOf(2), failed)
        }
    }

    @Test
    fun `large restoration commits bounded batches without individual rewrites`() = runTest {
        val batches = mutableListOf<Int>()
        val progress = mutableListOf<Int>()
        restoreInBatches(
            entries = (1..251).toList(),
            restoreBatch = { batches += it.size },
            restoreEntry = { error("A successful batch must not be repeated") },
            onError = { _, _ -> error("No errors expected") },
            onBatchComplete = { progress += it.size },
        )
        assertEquals(listOf(100, 100, 51), batches)
        assertEquals(batches, progress)
    }

    @Test
    fun `one invalid entry does not lose healthy entries from a rolled-back batch`() = runTest {
        val rows = mutableListOf<Int>()
        val failures = mutableListOf<Int>()
        var progress = 0
        restoreInBatches(
            entries = (1..6).toList(),
            batchSize = 3,
            restoreBatch = { chunk ->
                val snapshot = rows.toList()
                try {
                    for (item in chunk) {
                        if (item == 2) throw IllegalArgumentException("Invalid item")
                        rows += item
                    }
                } catch (error: Exception) {
                    rows.clear()
                    rows += snapshot
                    throw error
                }
            },
            restoreEntry = {
                if (it == 2) throw IllegalArgumentException("Invalid item")
                rows += it
            },
            onError = { entry, _ -> failures += entry },
            onBatchComplete = { progress += it.size },
        )
        assertEquals(listOf(1, 3, 4, 5, 6), rows)
        assertEquals(listOf(2), failures)
        assertEquals(6, progress)
    }

    @Test
    fun `cancellation never falls back to rewriting the rest of a backup`() {
        assertThrows(CancellationException::class.java) {
            runTest {
                restoreInBatches(
                    entries = listOf(1, 2),
                    restoreBatch = { throw CancellationException("Stopped") },
                    restoreEntry = { error("Cancelled work must not continue") },
                    onError = { _, _ -> error("Cancellation is not a broken entry") },
                    onBatchComplete = { error("Cancelled work is not complete") },
                )
            }
        }
    }
}

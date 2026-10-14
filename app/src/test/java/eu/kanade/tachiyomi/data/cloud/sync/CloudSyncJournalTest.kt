package eu.kanade.tachiyomi.data.cloud.sync

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.mi.data.AnimeDatabase

class CloudSyncJournalTest {
    @Test fun userWritesAndJournalRollBackTogetherAndRemoteWritesDoNotEcho() = both { db, anime ->
        enable(db)
        val table = if (anime) "animes" else "mangas"
        db.sql("BEGIN")
        db.sql("UPDATE $table SET favorite=1 WHERE _id=1")
        assertEquals(1L, db.count())
        db.sql("ROLLBACK")
        assertEquals(0L, db.count())
        db.sql("UPDATE cloud_sync_control SET suppressed=1")
        db.sql("UPDATE $table SET favorite=1 WHERE _id=1")
        assertEquals(0L, db.count())
        db.sql("UPDATE cloud_sync_control SET suppressed=0")
        db.sql("UPDATE $table SET favorite=0 WHERE _id=1")
        assertEquals(1L, db.count())
    }

    @Test fun disabledAndIncognitoWritesRemainLocalAndSourcePrivacyIsRespected() = both { db, anime ->
        val table = if (anime) "animes" else "mangas"
        db.sql("UPDATE $table SET favorite=1 WHERE _id=1")
        assertEquals(0L, db.count())
        enable(db)
        db.sql("UPDATE cloud_sync_control SET incognito=1")
        db.sql("UPDATE $table SET favorite=0 WHERE _id=1")
        assertEquals(0L, db.count())
        db.sql("UPDATE cloud_sync_control SET incognito=0")
        db.sql("INSERT INTO cloud_sync_excluded_sources VALUES(73)")
        db.sql("UPDATE $table SET favorite=1 WHERE _id=1")
        assertEquals(0L, db.count())
    }

    @Test fun repeatedProgressCoalescesAndAcknowledgmentCannotLoseAConcurrentWrite() = both { db, anime ->
        enable(db)
        val table = if (anime) "episodes" else "chapters"
        val column = if (anime) "last_second_seen" else "last_page_read"
        db.sql("UPDATE $table SET $column=20 WHERE _id=2")
        val first = db.scalar("SELECT revision FROM cloud_sync_dirty WHERE kind='item'")
        db.sql("UPDATE $table SET $column=3 WHERE _id=2")
        db.sql("DELETE FROM cloud_sync_dirty WHERE revision=$first")
        assertEquals(1L, db.count())
        assertTrue(db.scalar("SELECT revision FROM cloud_sync_dirty WHERE kind='item'") > first)
        db.sql("UPDATE $table SET $column=4, is_syncing=1 WHERE _id=2")
        assertEquals(1L, db.count())
    }

    @Test fun deletingATitleRetainsItsStableReferenceAfterTheRowIsGone() = both { db, anime ->
        enable(db)
        db.sql("DELETE FROM ${if (anime) "animes" else "mangas"} WHERE _id=1")
        assertEquals(1L, db.scalar("SELECT removed FROM cloud_sync_dirty WHERE kind='title'"))
        assertEquals(1L, db.scalar("SELECT count(*) FROM cloud_sync_dirty WHERE source=73 AND title='/entry/1'"))
    }

    @Test fun offlineJournalRetainsWhenTheEditHappened() = both { db, anime ->
        enable(db)
        val before = System.currentTimeMillis() / 1000 * 1000
        val table = if (anime) "episodes" else "chapters"
        val column = if (anime) "last_second_seen" else "last_page_read"
        db.sql("UPDATE $table SET $column=12 WHERE _id=2")
        val saved = db.scalar("SELECT changed_at FROM cloud_sync_dirty WHERE kind='item'")
        assertTrue(saved in before..System.currentTimeMillis())
        // Waiting for a connection must not replace this with capture/upload time.
        assertEquals(saved, db.scalar("SELECT changed_at FROM cloud_sync_dirty WHERE kind='item'"))
    }

    @Test fun repeatedHistoryUpsertsSaveLocallyAndReplaceOnlyTheirJournalRevision() = both { db, anime ->
        enable(db)
        verifyHistoryUpserts(db, anime)
    }

    @Test fun migrationRepairsExistingHistoryTriggersWithoutDeletingPendingChanges() = both { db, anime ->
        enable(db)
        val table = if (anime) "animehistory" else "history"
        val item = if (anime) "episode_id" else "chapter_id"
        val at = if (anime) "last_seen" else "last_read"
        val titles = if (anime) "animes" else "mangas"
        val items = if (anime) "episodes" else "chapters"
        val owner = if (anime) "anime_id" else "manga_id"
        listOf("insert", "update").forEach { operation ->
            db.sql("DROP TRIGGER cloud_sync_history_$operation")
            db.sql(
                "CREATE TRIGGER cloud_sync_history_$operation AFTER ${operation.uppercase()} ON $table " +
                    "BEGIN INSERT OR REPLACE INTO cloud_sync_dirty(kind,source,title,item,removed) " +
                    "SELECT 'history',T.source,T.url,I.url,0 FROM $titles T JOIN $items I " +
                    "ON I.$owner=T._id WHERE I._id=new.$item; END",
            )
        }
        insertHistory(db, anime, 100, upsert = false)
        val revision = db.scalar("SELECT revision FROM cloud_sync_dirty WHERE kind='history'")
        val schema = if (anime) AnimeDatabase.Schema else Database.Schema
        // Test the immutable history repair, even after unrelated migrations are added.
        val historyRepairVersion = if (anime) 149L else 39L
        schema.migrate(db, historyRepairVersion, historyRepairVersion + 1)
        assertEquals(revision, db.scalar("SELECT revision FROM cloud_sync_dirty WHERE kind='history'"))
        verifyHistoryUpserts(db, anime)
    }

    private fun verifyHistoryUpserts(db: JdbcSqliteDriver, anime: Boolean) {
        val table = if (anime) "animehistory" else "history"
        val item = if (anime) "episode_id" else "chapter_id"
        val at = if (anime) "last_seen" else "last_read"
        insertHistory(db, anime, 200)
        val revision = db.scalar("SELECT revision FROM cloud_sync_dirty WHERE kind='history'")
        insertHistory(db, anime, 300)
        assertEquals(300L, db.scalar("SELECT $at FROM $table WHERE $item=2"))
        assertEquals(1L, db.scalar("SELECT count(*) FROM cloud_sync_dirty WHERE kind='history'"))
        assertTrue(db.scalar("SELECT revision FROM cloud_sync_dirty WHERE kind='history'") > revision)
        db.sql("DELETE FROM cloud_sync_dirty WHERE revision=$revision")
        assertEquals(1L, db.scalar("SELECT count(*) FROM cloud_sync_dirty WHERE kind='history'"))
    }

    private fun insertHistory(db: JdbcSqliteDriver, anime: Boolean, timestamp: Long, upsert: Boolean = true) {
        val table = if (anime) "animehistory" else "history"
        val item = if (anime) "episode_id" else "chapter_id"
        val at = if (anime) "last_seen" else "last_read"
        val extraColumn = if (anime) "" else ",time_read"
        val extraValue = if (anime) "" else ",0"
        val conflict = if (upsert) " ON CONFLICT($item) DO UPDATE SET $at=excluded.$at" else ""
        db.sql("INSERT INTO $table($item,$at$extraColumn) VALUES(2,$timestamp$extraValue)$conflict")
    }

    private fun enable(db: JdbcSqliteDriver) = db.sql("UPDATE cloud_sync_control SET enabled=1")

    @Test fun releaseSubscriptionsAreJournaledWithoutAddingATitleToTheLibrary() = both { db, _ ->
        enable(db)
        db.sql("INSERT INTO release_subscription(entry_id,mode,availability,reminder) VALUES(1,'FOLLOW',1,0)")
        assertEquals(1L, db.scalar("SELECT count(*) FROM cloud_sync_dirty WHERE kind='subscription' AND source=73"))
        db.sql("UPDATE release_subscription SET mode='IGNORE' WHERE entry_id=1")
        assertEquals(1L, db.count())
        db.sql("DELETE FROM release_subscription WHERE entry_id=1")
        assertEquals(1L, db.scalar("SELECT removed FROM cloud_sync_dirty WHERE kind='subscription'"))
        db.sql("DELETE FROM cloud_sync_dirty")
        db.sql("UPDATE cloud_sync_control SET suppressed=1")
        db.sql("INSERT INTO release_subscription(entry_id,mode) VALUES(1,'FOLLOW')")
        assertEquals(0L, db.count())
    }

    private fun both(test: (JdbcSqliteDriver, Boolean) -> Unit) {
        listOf(false, true).forEach { anime ->
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { db ->
                if (anime) AnimeDatabase.Schema.create(db) else Database.Schema.create(db)
                val table = if (anime) "animes" else "mangas"
                val flags = if (anime) "episode_flags" else "chapter_flags"
                val extra = if (anime) {
                    ",season_flags,season_number,season_source_order,background_last_modified"
                } else {
                    ""
                }
                val extraValues = if (anime) ",0,0,0,0" else ""
                db.sql(
                    "INSERT INTO $table(_id,source,url,title,status,favorite,initialized,viewer,$flags," +
                        "cover_last_modified,date_added$extra) VALUES(1,73,'/entry/1','Example',0,0,1,0,0,0,0$extraValues)",
                )
                if (anime) {
                    db.sql(
                        "INSERT INTO episodes(_id,anime_id,url,name,seen,bookmark,last_second_seen,total_seconds," +
                            "episode_number,source_order,date_fetch,date_upload,fillermark) " +
                            "VALUES(2,1,'/item/2','Part 2',0,0,0,0,2,0,0,0,0)",
                    )
                } else {
                    db.sql(
                        "INSERT INTO chapters(_id,manga_id,url,name,read,bookmark,last_page_read," +
                            "chapter_number,source_order,date_fetch,date_upload) " +
                            "VALUES(2,1,'/item/2','Part 2',0,0,0,2,0,0,0)",
                    )
                }
                test(db, anime)
            }
        }
    }

    private fun JdbcSqliteDriver.sql(sql: String) {
        execute(null, sql, 0)
    }
    private fun JdbcSqliteDriver.count(): Long = scalar("SELECT count(*) FROM cloud_sync_dirty")
    private fun JdbcSqliteDriver.scalar(sql: String): Long =
        executeQuery(null, sql, { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0)!!)
        }, 0).value
}

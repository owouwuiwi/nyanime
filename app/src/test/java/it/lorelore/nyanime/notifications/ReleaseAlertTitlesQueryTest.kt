package it.lorelore.nyanime.notifications

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.mi.data.AnimeDatabase
import java.io.File

class ReleaseAlertTitlesQueryTest {
    @Test
    fun `settings retain muted titles and include library started and inherited seasons`() {
        for (anime in listOf(false, true)) {
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { db ->
                if (anime) AnimeDatabase.Schema.create(db) else Database.Schema.create(db)
                val entries = if (anime) "animes" else "mangas"
                val items = if (anime) "episodes" else "chapters"
                val owner = if (anime) "anime_id" else "manga_id"
                val flags = if (anime) "episode_flags" else "chapter_flags"
                val extras = if (anime) {
                    ",season_flags,season_number,season_source_order,background_last_modified"
                } else {
                    ""
                }
                val extraValues = if (anime) ",0,0,0,0" else ""
                for (id in 1..6) {
                    val favorite = if (id == 1) 1 else 0
                    db.execute(
                        null,
                        """
                        INSERT INTO $entries(
                            _id,source,url,title,status,favorite,initialized,viewer,$flags,
                            cover_last_modified,date_added$extras
                        ) VALUES($id,71,'/entry/$id','Fictional title $id',0,$favorite,1,0,0,0,0$extraValues)
                        """.trimIndent(),
                        0,
                    )
                }
                db.execute(null, "INSERT INTO release_subscription VALUES(2,'AUTO',0,0)", 0)
                db.execute(null, "INSERT INTO release_subscription VALUES(3,'IGNORE',1,1)", 0)
                val itemFlags = if (anime) {
                    "seen,last_second_seen,total_seconds,episode_number,fillermark"
                } else {
                    "read,last_page_read,chapter_number"
                }
                val itemValues = if (anime) "1,12,100,1,1" else "1,12,1"
                db.execute(
                    null,
                    """
                    INSERT INTO $items(
                        _id,$owner,url,name,bookmark,$itemFlags,source_order,date_fetch,date_upload
                    ) VALUES(5,5,'/part/1','Part 1',0,$itemValues,0,1,1)
                    """.trimIndent(),
                    0,
                )
                if (anime) db.execute(null, "UPDATE animes SET parent_id=1 WHERE _id=6", 0)
                val root = generateSequence(File(System.getProperty("user.dir")!!)) { it.parentFile }
                    .first { File(it, "settings.gradle.kts").isFile }
                val path = if (anime) {
                    "data/src/main/sqldelightanime/dataanime/releaseMonitor.sq"
                } else {
                    "data/src/main/sqldelight/data/releaseMonitor.sq"
                }
                val sql = File(root, path).readText().substringAfter("getAlertTitles:").substringBefore(';').trim()
                val ids = db.executeQuery(null, sql, { cursor ->
                    QueryResult.Value(buildList { while (cursor.next().value) add(cursor.getLong(0)!!) })
                }, 0).value
                assertEquals(if (anime) listOf(1L, 2L, 3L, 5L, 6L) else listOf(1L, 2L, 3L, 5L), ids)
            }
        }
    }
}

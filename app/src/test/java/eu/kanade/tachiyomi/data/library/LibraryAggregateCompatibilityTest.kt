@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.data.library

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.mi.data.AnimeDatabase
import java.io.File

class LibraryAggregateCompatibilityTest {
    @Test
    fun `bounded library query preserves categories excluded scanlators history and season counts`() {
        listOf(false, true).forEach { anime ->
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { db ->
                if (anime) AnimeDatabase.Schema.create(db) else Database.Schema.create(db)
                val entries = if (anime) "animes" else "mangas"
                val items = if (anime) "episodes" else "chapters"
                val owner = if (anime) "anime_id" else "manga_id"
                val flags = if (anime) "episode_flags" else "chapter_flags"
                val extra = if (anime) ",season_flags,season_number,season_source_order,background_last_modified" else ""
                val extraValues = if (anime) ",0,0,0,0" else ""
                (1..5).forEach { id ->
                    db.execute(
                        null,
                        "INSERT INTO $entries(_id,source,url,title,status,favorite,initialized,viewer,$flags,cover_last_modified,date_added$extra) VALUES($id,73,'/entry/$id','Title $id',0,${if (id in listOf(
                                1,
                                3,
                                5,
                            )
                        ) {
                            1
                        } else {
                            0
                        }},1,0,0,0,0$extraValues)",
                        0,
                    )
                }
                if (anime) {
                    db.execute(null, "UPDATE animes SET fetch_type=0 WHERE _id=3", 0)
                    db.execute(null, "UPDATE animes SET parent_id=3 WHERE _id=2", 0)
                }
                val itemFlags = if (anime) "seen,last_second_seen,total_seconds,episode_number,fillermark" else "read,last_page_read,chapter_number"
                val itemValues = if (anime) "1,12,100,1,1" else "1,12,1"
                listOf(1, 2, 4).forEach { id ->
                    db.execute(
                        null,
                        "INSERT INTO $items(_id,$owner,url,name,bookmark,$itemFlags,source_order,date_fetch,date_upload) VALUES($id,$id,'/item/$id','Part $id',1,$itemValues,0,10,20)",
                        0,
                    )
                }
                if (!anime) {
                    db.execute(null, "INSERT INTO excluded_scanlators(manga_id,scanlator) VALUES(1,'excluded')", 0)
                    db.execute(
                        null,
                        "INSERT INTO chapters(_id,manga_id,url,name,scanlator,read,bookmark,last_page_read,chapter_number,source_order,date_fetch,date_upload) VALUES(9,1,'/item/9','Part 9','excluded',0,1,0,9,0,30,40)",
                        0,
                    )
                    db.execute(null, "INSERT INTO history(chapter_id,last_read,time_read) VALUES(9,200,1)", 0)
                }
                val history = if (anime) "animehistory" else "history"
                val itemId = if (anime) "episode_id" else "chapter_id"
                val last = if (anime) "last_seen" else "last_read"
                listOf(1, 2, 4).forEach {
                    val extraHistory = if (anime) "" else ",time_read"
                    val extraHistoryValue = if (anime) "" else ",1"
                    db.execute(
                        null,
                        "INSERT INTO $history($itemId,$last$extraHistory) VALUES($it,100$extraHistoryValue)",
                        0,
                    )
                }
                val categories = if (anime) "animes_categories" else "mangas_categories"
                listOf(4, 5).forEach {
                    db.execute(null, "INSERT INTO categories(_id,name,sort,flags) VALUES($it,'Category $it',0,0)", 0)
                    db.execute(null, "INSERT INTO $categories($owner,category_id) VALUES(1,$it)", 0)
                }
                val view = if (anime) "animelibView" else "libraryView"
                val queryName = if (anime) "animelib:" else "library:"
                val path = if (anime) "data/src/main/sqldelightanime/view/$view.sq" else "data/src/main/sqldelight/view/$view.sq"
                val root = generateSequence(File(System.getProperty("user.dir")!!)) { it.parentFile }
                    .first { File(it, "settings.gradle.kts").isFile }
                val query = File(root, path).readText().substringAfter(queryName).trim().removeSuffix(";")
                val columns =
                    "_id,category,totalCount,${if (anime) "seenCount" else "readCount"},latestUpload,${if (anime) "episodeFetchedAt,lastSeen" else "chapterFetchedAt,lastRead"},bookmarkCount" +
                        if (anime) ",fillermarkCount" else ""
                fun rows(
                    from: String,
                ) = db.executeQuery(null, "SELECT $columns FROM $from ORDER BY _id,category", { cursor ->
                    QueryResult.Value(
                        buildList {
                            while (cursor.next().value) {
                                add(
                                    (0 until columns.split(',').size).map {
                                        cursor.getLong(it)
                                    },
                                )
                            }
                        },
                    )
                }, 0).value
                val original = rows(view)
                assertEquals(original, rows("($query)"))
                assertEquals(4, original.size)
            }
        }
    }
}

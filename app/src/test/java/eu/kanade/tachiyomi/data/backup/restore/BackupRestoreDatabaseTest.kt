package eu.kanade.tachiyomi.data.backup.restore

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import data.History
import data.Mangas
import dataanime.Animehistory
import dataanime.Animes
import dataanime.Episodes
import eu.kanade.domain.entries.anime.interactor.UpdateAnime
import eu.kanade.domain.entries.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import eu.kanade.tachiyomi.data.backup.models.BackupAnimeHistory
import eu.kanade.tachiyomi.data.backup.models.BackupAnimeTracking
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupEpisode
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import eu.kanade.tachiyomi.data.backup.restore.restorers.AnimeRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.discovery.SqlEpisodeEndingCueStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import tachiyomi.data.AnimeUpdateStrategyColumnAdapter
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.FetchTypeColumnAdapter
import tachiyomi.data.MangaUpdateStrategyColumnAdapter
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.category.anime.AnimeCategoryRepositoryImpl
import tachiyomi.data.category.manga.MangaCategoryRepositoryImpl
import tachiyomi.data.entries.anime.AnimeRepositoryImpl
import tachiyomi.data.entries.manga.MangaRepositoryImpl
import tachiyomi.data.handlers.anime.AndroidAnimeDatabaseHandler
import tachiyomi.data.handlers.manga.AndroidMangaDatabaseHandler
import tachiyomi.data.items.chapter.ChapterRepositoryImpl
import tachiyomi.data.items.episode.EpisodeRepositoryImpl
import tachiyomi.data.track.anime.AnimeTrackRepositoryImpl
import tachiyomi.data.track.manga.MangaTrackRepositoryImpl
import tachiyomi.domain.category.anime.interactor.GetAnimeCategories
import tachiyomi.domain.category.manga.interactor.GetMangaCategories
import tachiyomi.domain.entries.anime.interactor.AnimeFetchInterval
import tachiyomi.domain.entries.anime.interactor.GetAnimeByUrlAndSourceId
import tachiyomi.domain.entries.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.entries.manga.interactor.MangaFetchInterval
import tachiyomi.domain.items.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.items.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.track.anime.interactor.GetAnimeTracks
import tachiyomi.domain.track.anime.interactor.InsertAnimeTrack
import tachiyomi.domain.track.manga.interactor.GetMangaTracks
import tachiyomi.domain.track.manga.interactor.InsertMangaTrack
import tachiyomi.mi.data.AnimeDatabase
import java.io.File
import java.util.zip.GZIPInputStream

class BackupRestoreDatabaseTest {
    @Test
    fun `portable backup restores progress and relations without duplicates through actual restorers`() = runBlocking {
        val backup = fixture()
        restoreManga(backup)
        restoreAnime(backup)
    }

    private fun fixture(): Backup {
        // A local validation payload stays outside Git. CI always uses source-independent synthetic entries.
        val privateFile = System.getenv("NYANIME_VALIDATION_BACKUP")?.let(::File)
        if (privateFile?.isFile == true) {
            val bytes = privateFile.inputStream().use { input ->
                val buffered = input.buffered()
                buffered.mark(2)
                val gzipped = buffered.read() == 0x1f && buffered.read() == 0x8b
                buffered.reset()
                if (gzipped) GZIPInputStream(buffered).use { it.readBytes() } else buffered.readBytes()
            }
            return ProtoBuf.decodeFromByteArray(Backup.serializer(), bytes)
        }
        val entries = (1..250).map { id ->
            BackupAnime(
                42,
                "/entry/$id",
                "Video $id",
                id = id.toLong(),
                categories = listOf(3),
                history = listOf(BackupAnimeHistory("/shared/item", 1_760_000_000_000 + id)),
                tracking = listOf(BackupAnimeTracking(17, id.toLong(), mediaId = id.toLong(), lastEpisodeSeen = 2f)),
                episodes = listOf(
                    BackupEpisode("/shared/item", "Part", lastSecondSeen = 81_000, bookmark = true, fillermark = true),
                ),
            )
        }
        entries[0].fetchType = FetchType.Seasons
        val child = BackupAnime(
            42,
            "/season/1",
            "Season",
            id = 1000,
            parentId = 1,
            episodes = listOf(BackupEpisode("/item/season", "Part", lastSecondSeen = 12_000)),
        )
        val source = Backup(
            backupAnime = entries + child,
            backupManga = (1..250).map { id ->
                BackupManga(
                    84,
                    "/entry/$id",
                    "Book $id",
                    categories = listOf(3),
                    viewer_flags = 9,
                    history = listOf(BackupHistory("/shared/item", 1_760_000_000_000 + id, 3000)),
                    tracking = listOf(BackupTracking(17, id.toLong(), mediaId = id.toLong(), lastChapterRead = 2f)),
                    chapters = listOf(BackupChapter("/shared/item", "Part", lastPageRead = 7, bookmark = true)),
                )
            },
            backupAnimeCategories = listOf(BackupCategory("Video category", order = 3, id = 3)),
            backupCategories = listOf(BackupCategory("Reading category", order = 3, id = 3)),
        )
        return ProtoBuf.decodeFromByteArray(
            Backup.serializer(),
            ProtoBuf.encodeToByteArray(Backup.serializer(), source),
        )
    }

    private fun categories(driver: JdbcSqliteDriver, entries: List<BackupCategory>) {
        entries.filter { it.id > 0 }.forEach { category ->
            driver.execute(null, "INSERT INTO categories(_id,name,sort,flags) VALUES(?,?,?,?)", 4) {
                bindLong(0, category.id)
                bindString(1, category.name)
                bindLong(2, category.order)
                bindLong(3, category.flags)
            }
        }
    }

    private suspend fun restoreManga(backup: Backup) {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            Database.Schema.create(driver)
            categories(driver, backup.backupCategories)
            val handler = AndroidMangaDatabaseHandler(
                Database(
                    driver,
                    History.Adapter(DateColumnAdapter),
                    Mangas.Adapter(StringListColumnAdapter, MangaUpdateStrategyColumnAdapter),
                ),
                driver,
            )
            val entries = MangaRepositoryImpl(handler)
            val chapters = GetChaptersByMangaId(ChapterRepositoryImpl(handler))
            val fetch = MangaFetchInterval(chapters)
            val tracks = MangaTrackRepositoryImpl(handler)
            val getCategories = GetMangaCategories(MangaCategoryRepositoryImpl(handler))
            val restorer = MangaRestorer(
                handler,
                getCategories,
                GetMangaByUrlAndSourceId(entries),
                chapters,
                UpdateManga(entries, fetch),
                GetMangaTracks(tracks),
                InsertMangaTrack(tracks),
                fetch,
            )
            suspend fun apply() = restoreInBatches(
                backup.backupManga,
                restoreBatch = { restorer.restoreBatch(it, backup.backupCategories) },
                restoreEntry = { restorer.restore(it, backup.backupCategories) },
                onError = { _, error -> throw IllegalStateException("A valid entry was not restored", error) },
                onBatchComplete = {},
            )
            apply()
            val ids = backup.backupManga.map { entry ->
                val restored = entries.getMangaByUrlAndSourceId(entry.url, entry.source)!!
                assertEquals((entry.viewer_flags ?: entry.viewer).toLong(), restored.viewerFlags)
                val expectedCategories = backup.backupCategories.filter {
                    it.order in entry.categories
                }.map { it.name }.toSet()
                assertEquals(expectedCategories, getCategories.await(restored.id).map { it.name }.toSet())
                val restoredTracks = tracks.getTracksByMangaId(restored.id).associateBy { it.trackerId }
                entry.tracking.forEach {
                    assertEquals(it.getTrackImpl().remoteId, restoredTracks[it.syncId.toLong()]!!.remoteId)
                    assertEquals(it.lastChapterRead.toDouble(), restoredTracks[it.syncId.toLong()]!!.lastChapterRead)
                }
                entry.history.forEach {
                    val history = handler.awaitList { historyQueries.getHistoryByMangaId(restored.id) }
                        .find { row ->
                            row.chapter_id ==
                                chapters.await(restored.id).find { chapter -> chapter.url == it.url }?.id
                        }
                    if (entry.chapters.any { chapter -> chapter.url == it.url }) {
                        assertNotNull(history)
                        assertEquals(it.lastRead, history!!.last_read?.time)
                        assertEquals(it.readDuration, history.time_read)
                    }
                }
                val items = chapters.await(restored.id).associateBy { it.url }
                entry.chapters.forEach {
                    assertEquals(it.lastPageRead, items[it.url]!!.lastPageRead)
                    assertEquals(it.bookmark, items[it.url]!!.bookmark)
                    assertEquals(it.read, items[it.url]!!.read)
                }
                restored.id
            }
            apply()
            assertEquals(ids, backup.backupManga.map { entries.getMangaByUrlAndSourceId(it.url, it.source)!!.id })
            assertEquals(ids.distinct().size, handler.awaitList { mangasQueries.getAllMangaSourceAndUrl() }.size)
        }
    }

    private suspend fun restoreAnime(backup: Backup) {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            AnimeDatabase.Schema.create(driver)
            categories(driver, backup.backupAnimeCategories)
            val handler = AndroidAnimeDatabaseHandler(
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
            val entries = AnimeRepositoryImpl(handler)
            val episodes = GetEpisodesByAnimeId(EpisodeRepositoryImpl(handler))
            val fetch = AnimeFetchInterval(episodes)
            val tracks = AnimeTrackRepositoryImpl(handler)
            val getCategories = GetAnimeCategories(AnimeCategoryRepositoryImpl(handler))
            val restorer = AnimeRestorer(
                handler, getCategories,
                GetAnimeByUrlAndSourceId(entries), episodes, UpdateAnime(entries, fetch),
                GetAnimeTracks(tracks), InsertAnimeTrack(tracks), SqlEpisodeEndingCueStore(handler), fetch,
            )
            val byId = backup.backupAnime.mapNotNull { entry -> entry.id?.let { it to entry } }.toMap()
            val seasons = backup.backupAnime.filter { it.parentId in byId }.groupBy { it.parentId }
            val roots = backup.backupAnime.filter { it.parentId !in byId }
            suspend fun apply() = restoreInBatches(
                roots,
                restoreBatch = { restorer.restoreBatch(it, backup.backupAnimeCategories, seasons) },
                restoreEntry = { restorer.restore(it, backup.backupAnimeCategories, seasons[it.id].orEmpty()) },
                onError = { _, error -> throw IllegalStateException("A valid entry was not restored", error) },
                onBatchComplete = {},
            )
            apply()
            val ids = backup.backupAnime.map { entry ->
                val restored = entries.getAnimeByUrlAndSourceId(entry.url, entry.source)!!
                assertEquals(MemoColumnAdapter.decode(entry.memo), restored.memo)
                val expectedCategories = backup.backupAnimeCategories.filter {
                    it.order in entry.categories
                }.map { it.name }.toSet()
                assertEquals(expectedCategories, getCategories.await(restored.id).map { it.name }.toSet())
                val restoredTracks = tracks.getTracksByAnimeId(restored.id).associateBy { it.trackerId }
                entry.tracking.forEach {
                    assertEquals(it.getTrackImpl().remoteId, restoredTracks[it.syncId.toLong()]!!.remoteId)
                    assertEquals(it.lastEpisodeSeen.toDouble(), restoredTracks[it.syncId.toLong()]!!.lastEpisodeSeen)
                }
                entry.history.forEach {
                    val history = handler.awaitList { animehistoryQueries.getHistoryByAnimeId(restored.id) }
                        .find { row ->
                            row.episode_id ==
                                episodes.await(restored.id).find { episode -> episode.url == it.url }?.id
                        }
                    if (entry.episodes.any { episode -> episode.url == it.url }) {
                        assertNotNull(history)
                        assertEquals(it.lastRead, history!!.last_seen?.time)
                    }
                }
                byId[entry.parentId]?.let { parent ->
                    assertEquals(entries.getAnimeByUrlAndSourceId(parent.url, parent.source)!!.id, restored.parentId)
                }
                val items = episodes.await(restored.id).associateBy { it.url }
                entry.episodes.forEach {
                    assertEquals(it.lastSecondSeen, items[it.url]!!.lastSecondSeen)
                    assertEquals(it.fillermark, items[it.url]!!.fillermark)
                    assertEquals(it.bookmark, items[it.url]!!.bookmark)
                    assertEquals(it.seen, items[it.url]!!.seen)
                    if (it.endingDurationMs > 0 &&
                        it.endingStartMs > 0 &&
                        it.endingEndMs > it.endingStartMs &&
                        it.endingEndMs <= it.endingDurationMs
                    ) {
                        assertNotNull(SqlEpisodeEndingCueStore(handler).get(items[it.url]!!.id))
                    }
                }
                restored.id
            }
            apply()
            assertEquals(ids, backup.backupAnime.map { entries.getAnimeByUrlAndSourceId(it.url, it.source)!!.id })
            assertEquals(ids.distinct().size, handler.awaitList { animesQueries.getAllAnimeSourceAndUrl() }.size)
        }
    }
}

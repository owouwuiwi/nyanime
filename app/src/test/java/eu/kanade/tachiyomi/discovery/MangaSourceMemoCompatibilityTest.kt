package eu.kanade.tachiyomi.discovery

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.MangaSource
import eu.kanade.tachiyomi.source.MangaSourceUpdateGate
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation

class MangaSourceMemoCompatibilityTest {
    @Test
    fun concurrentUpdateRequestsForOneTitleNeverOverlap() = runBlocking {
        val manga = SManga.create().apply {
            url = "/same-work"
            title = "Test work"
        }
        val inFlight = AtomicInteger()
        val maximum = AtomicInteger()
        val source = object : MangaSource {
            override val id = 12L
            override val name = "Test source"
            override suspend fun getMangaUpdate(
                manga: SManga,
                chapters: List<SChapter>,
                fetchDetails: Boolean,
                fetchChapters: Boolean,
            ): SMangaUpdate {
                val active = inFlight.incrementAndGet()
                maximum.updateAndGet { maxOf(it, active) }
                try {
                    delay(15)
                    return SMangaUpdate(manga, chapters)
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        }
        (1..6).map {
            async { MangaSourceUpdateGate.await(source, manga, emptyList(), true, false) }
        }.awaitAll()
        assertEquals(1, maximum.get())
    }

    @Test
    fun combinedUpdateExposesTheManga16AbiAndKeepsOlderSourcesWorking() = runBlocking {
        assertEquals(true, Source::class.java.isAssignableFrom(CatalogueSource::class.java))
        assertNotNull(HttpSource::class.java.getMethod("getHomeUrl"))
        assertNotNull(
            MangaSource::class.java.getMethod(
                "getMangaUpdate",
                SManga::class.java,
                List::class.java,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Continuation::class.java,
            ),
        )
        assertNotNull(SMangaUpdate::class.java.getConstructor(SManga::class.java, List::class.java))

        val manga = SManga.create().apply {
            url = "/work"
            title = "Test work"
        }
        val chapter = SChapter.create().apply {
            url = "/chapter"
            name = "Test chapter"
        }
        var detailCalls = 0
        var chapterCalls = 0
        val olderSource = object : MangaSource {
            override val id = 1L
            override val name = "Test source"
            override suspend fun getMangaDetails(manga: SManga): SManga {
                detailCalls++
                return manga
            }
            override suspend fun getChapterList(manga: SManga): List<SChapter> {
                chapterCalls++
                return listOf(chapter)
            }
        }
        val update = olderSource.getMangaUpdate(manga, emptyList(), true, true)
        assertEquals(manga, update.manga)
        assertEquals(listOf(chapter), update.chapters)
        assertEquals(1, detailCalls)
        assertEquals(1, chapterCalls)
    }

    @Test
    fun mangaAndChapterExposeTheGenericMemoAbiUsedByExtensions() {
        assertNotNull(SManga::class.java.getMethod("setMemo", JsonObject::class.java))
        assertNotNull(SChapter::class.java.getMethod("setMemo", JsonObject::class.java))

        val memo = JsonObject(mapOf("test.id" to JsonPrimitive("42")))
        val manga = SManga.create().apply {
            url = "/work"
            title = "Test work"
            this.memo = memo
        }
        assertEquals(memo, manga.copy().memo)

        val chapter = SChapter.create().apply {
            url = "/chapter"
            name = "Test chapter"
            this.memo = memo
        }
        val copied = SChapter.create().apply { copyFrom(chapter) }
        assertEquals(memo, copied.memo)
    }
}

package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.items.chapter.model.Chapter
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HttpPageLoaderTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `changing page closes a superseded body that ignores thread interruption`() = runBlocking {
        val source = mockk<HttpSource>()
        val cache = mockk<ChapterCache>()
        val preferences = mockk<SourcePreferences>()
        val dataSaver = mockk<Preference<SourcePreferences.DataSaver>>()
        every { preferences.dataSaver() } returns dataSaver
        every { dataSaver.get() } returns SourcePreferences.DataSaver.NONE
        val chapter = ReaderChapter(Chapter.create().copy(id = 1, mangaId = 5, url = "/chapter-1"))
        every { cache.getPageListFromCache(any()) } throws IOException("No cached list")
        coEvery { source.getPageList(any()) } returns List(8) {
            Page(it, imageUrl = "https://example.test/image-$it")
        }
        val stored = ConcurrentHashMap<String, Boolean>()
        every { cache.isImageInCache(any()) } answers { stored[firstArg<String>()] == true }
        every { cache.getImageFile(any()) } answers { directory.resolve("image").toFile() }
        every { cache.putPageListToCache(any(), any()) } returns Unit
        every { cache.putImageToCache(any(), any()) } answers {
            secondArg<Response>().body.string()
            stored[firstArg()] = true
        }
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val socket = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                entered.countDown()
                while (closed.count > 0) {
                    try {
                        closed.await()
                    } catch (_: InterruptedException) { /* Simulate a blocking socket. */ }
                }
                throw IOException("Closed")
            }
            override fun timeout() = Timeout.NONE
            override fun close() {
                closed.countDown()
            }
        }.buffer()
        val blockedBody = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength() = -1L
            override fun source(): BufferedSource = socket
        }
        coEvery { source.getImage(any()) } coAnswers {
            val page = firstArg<Page>()
            Response.Builder().request(Request.Builder().url(page.imageUrl!!).build())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(if (page.index == 0) blockedBody else "demo".toResponseBody()).build()
        }
        val loader = HttpPageLoader(chapter, source, cache, preferences)
        val pages = loader.getPages()
        chapter.state = ReaderChapter.State.Loaded(pages)
        try {
            loader.setActivePage(0)
            check(entered.await(3, TimeUnit.SECONDS))
            loader.setActivePage(6)
            check(closed.await(3, TimeUnit.SECONDS))
            withTimeout(5_000) { pages[6].statusFlow.first { it == Page.State.READY } }
            assertEquals(Page.State.QUEUE, pages[0].status)
        } finally {
            closed.countDown()
            loader.recycle()
        }
    }

    @Test
    fun `visible and prefetched pages share loads and preserve their chapter owner`() = runBlocking {
        val source = mockk<HttpSource>()
        val cache = mockk<ChapterCache>()
        val preferences = mockk<SourcePreferences>()
        val dataSaver = mockk<Preference<SourcePreferences.DataSaver>>()
        every { preferences.dataSaver() } returns dataSaver
        every { dataSaver.get() } returns SourcePreferences.DataSaver.NONE
        val chapter = ReaderChapter(Chapter.create().copy(id = 1, mangaId = 5, url = "/chapter-1"))
        every { cache.getPageListFromCache(any()) } throws IOException("No cached list")
        coEvery { source.getPageList(any()) } returns List(6) {
            Page(100 + it, imageUrl = "https://example.test/image-$it")
        }
        val stored = ConcurrentHashMap<String, Boolean>()
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        every { cache.isImageInCache(any()) } answers { stored[firstArg<String>()] == true }
        every { cache.getImageFile(any()) } answers { directory.resolve("image").toFile() }
        every { cache.putPageListToCache(any(), any()) } returns Unit
        every { cache.putImageToCache(any(), any()) } answers {
            secondArg<Response>().body.string()
            stored[firstArg()] = true
        }
        coEvery { source.getImage(any()) } coAnswers {
            val url = firstArg<Page>().imageUrl!!
            calls.computeIfAbsent(url) { AtomicInteger() }.incrementAndGet()
            Response.Builder().request(Request.Builder().url(url).build())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("demo".toResponseBody()).build()
        }
        val loader = HttpPageLoader(chapter, source, cache, preferences)
        val pages = loader.getPages()
        chapter.state = ReaderChapter.State.Loaded(pages)
        val subscriptions = listOf(0, 2).map { index -> launch { loader.loadPage(pages[index]) } }
        try {
            loader.setActivePage(2)
            withTimeout(5_000) {
                pages.forEach { page -> page.statusFlow.first { it == Page.State.READY } }
            }
            assertEquals((0..5).toList(), pages.map { it.index })
            assertEquals(List(6) { chapter }, pages.map { it.chapter })
            assertEquals(6, calls.size)
            assertEquals(List(6) { 1 }, calls.values.map { it.get() })
        } finally {
            subscriptions.forEach { it.cancelAndJoin() }
            loader.recycle()
        }
    }
}

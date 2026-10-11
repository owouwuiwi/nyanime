package eu.kanade.tachiyomi.data.download.anime

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ParallelVideoTransferTest {
    @TempDir lateinit var directory: File
    private class Store(val file: File) : VideoBlockStore {
        override var metadata: PartialVideoMetadata? = null

        @Volatile override var journal: VideoBlockJournal? = null
        override fun size() = file.length()
        override fun open(offset: Long): OutputStream {
            if (offset == 0L) RandomAccessFile(file, "rw").use { it.setLength(0) }
            return openBlock(offset)
        }
        override fun openBlock(offset: Long): OutputStream {
            val handle = RandomAccessFile(file, "rw").apply { seek(offset) }
            return object : OutputStream() {
                override fun write(value: Int) = handle.write(value)
                override fun write(buffer: ByteArray, offset: Int, length: Int) = handle.write(buffer, offset, length)
                override fun close() {
                    handle.fd.sync()
                    handle.close()
                }
            }
        }
        override fun digest(offset: Long, length: Long): String = RandomAccessFile(file, "r").use {
            it.seek(offset)
            val bytes = ByteArray(length.toInt())
            it.readFully(bytes)
            MessageDigest.getInstance("SHA-256").digest(bytes).hex()
        }
    }
    private val payload = ByteArray(6 * 1024 * 1024 + 173) { (it * 17).toByte() }
    private val headers = Headers.Builder().add("X-Test", "preserved").build()

    private fun server(
        requests: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue(),
        answer: (RecordedRequest, Long, Long) -> MockResponse? = { _, _, _ -> null },
    ) = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertEquals("preserved", request.headers["X-Test"])
                val range = request.headers["Range"]!!.removePrefix("bytes=").split('-').map(String::toLong)
                val (from, to) = range
                requests += "$from-$to"
                return answer(request, from, to) ?: MockResponse.Builder().code(206)
                    .addHeader("Content-Range", "bytes $from-$to/${payload.size}")
                    .addHeader("ETag", "\"stable\"")
                    .body(Buffer().write(payload, from.toInt(), (to - from + 1).toInt())).build()
            }
        }
        start()
    }

    @Test fun rangesReconstructExactOriginalAndRetainSourceHeaders() = runBlocking {
        server().use { server ->
            val store = Store(File(directory, "video"))
            val success = ParallelVideoTransfer(OkHttpClient(), store, 501, minimumSize = 0)
                .tryDownload(server.url("/video.mp4").toString(), headers) { _, _ -> }
            assertTrue(success)
            assertArrayEquals(payload, store.file.readBytes())
            assertEquals(4, store.journal!!.completed.size)
        }
    }

    @Test fun wrongRangesFallBackAndNeverExposeSparsePartialAsContiguous() = runBlocking {
        server { _, from, to ->
            if (to > 65_535) {
                MockResponse.Builder().code(206).addHeader("ETag", "\"stable\"")
                    .addHeader("Content-Range", "bytes ${from + 1}-$to/${payload.size}").body("invalid").build()
            } else {
                null
            }
        }.use { server ->
            val store = Store(File(directory, "video"))
            assertFalse(
                ParallelVideoTransfer(OkHttpClient(), store, 502, minimumSize = 0)
                    .tryDownload(server.url("/video.mp4").toString(), headers) { _, _ -> },
            )
            assertNull(store.journal)
            assertEquals(0L, store.size())
        }
    }

    @Test fun corruptedCompletedBlockIsDownloadedAgainWhileValidBlocksAreReused() = runBlocking {
        val requests = ConcurrentLinkedQueue<String>()
        server(requests).use { server ->
            val store = Store(File(directory, "video"))
            val transfer = ParallelVideoTransfer(OkHttpClient(), store, 503, minimumSize = 0)
            val url = server.url("/video.mp4").toString()
            transfer.tryDownload(url, headers) { _, _ -> }
            RandomAccessFile(store.file, "rw").use {
                it.seek(2_097_152)
                it.write(42)
            }
            requests.clear()
            assertTrue(transfer.tryDownload(url, headers) { _, _ -> })
            assertEquals(setOf("0-65535", "2097152-4194303"), requests.toSet())
            assertArrayEquals(payload, store.file.readBytes())
        }
    }

    @Test fun changedContentAfterProbeFallsBackWithoutPublishingMixedBlocks() = runBlocking {
        server { _, _, to ->
            if (to > 65_535) MockResponse.Builder().code(200).body("new content").build() else null
        }.use { server ->
            val store = Store(File(directory, "video"))
            assertFalse(
                ParallelVideoTransfer(OkHttpClient(), store, 504, minimumSize = 0)
                    .tryDownload(server.url("/video.mp4").toString(), headers) { _, _ -> },
            )
            assertEquals(0L, store.size())
        }
    }

    @Test fun serverIgnoringProbeOrWithoutStrongValidatorUsesSequentialRoute() = runBlocking {
        for (answer in listOf(
            MockResponse.Builder().code(200).body("whole video").build(),
            MockResponse.Builder().code(206).addHeader("Content-Range", "bytes 0-65535/${payload.size}")
                .addHeader("ETag", "W/\"weak\"").body("ignored").build(),
        )) {
            MockWebServer().use { server ->
                server.enqueue(answer)
                server.start()
                val store = Store(File(directory, "video"))
                assertFalse(
                    ParallelVideoTransfer(OkHttpClient(), store, 505, minimumSize = 0)
                        .tryDownload(server.url("/video.mp4").toString(), Headers.Builder().build()) { _, _ -> },
                )
                assertEquals(0L, store.size())
            }
        }
    }

    @Test fun insufficientStorageDoesNotStartBlocks() = runBlocking {
        server().use { server ->
            val store = Store(File(directory, "video"))
            assertThrows(VideoStorageException::class.java) {
                runBlocking {
                    ParallelVideoTransfer(OkHttpClient(), store, 506, { 1024 }, minimumSize = 0)
                        .tryDownload(server.url("/video.mp4").toString(), headers) { _, _ -> }
                }
            }
            assertNull(store.journal)
        }
    }

    @Test fun rejectedRangeProbeFallsBackWithoutDiscardingExistingSequentialPartial() = runBlocking {
        for (status in listOf(400, 405, 416)) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse.Builder().code(status).build())
                server.start()
                val store = Store(File(directory, "rejected-$status").apply { writeText("partial") })
                assertFalse(
                    ParallelVideoTransfer(OkHttpClient(), store, 507, minimumSize = 0)
                        .tryDownload(server.url("/video.mp4").toString(), headers) { _, _ -> },
                )
                assertEquals("partial", store.file.readText())
            }
        }
    }

    @Test fun cancellationKeepsVerifiedBlocksAndResumeOnlyTransfersTheRemainder() = runBlocking {
        val requests = ConcurrentLinkedQueue<String>()
        val slow = AtomicBoolean(true)
        server(requests) { _, from, to ->
            if (from > 0 && slow.get()) {
                MockResponse.Builder().code(206)
                    .addHeader("Content-Range", "bytes $from-$to/${payload.size}")
                    .addHeader("ETag", "\"stable\"")
                    .throttleBody(64 * 1024, 100, TimeUnit.MILLISECONDS)
                    .body(Buffer().write(payload, from.toInt(), (to - from + 1).toInt())).build()
            } else {
                null
            }
        }.use { server ->
            val store = Store(File(directory, "interrupted"))
            val transfer = ParallelVideoTransfer(OkHttpClient(), store, 508, minimumSize = 0)
            val url = server.url("/video.mp4").toString()
            val running = launch { transfer.tryDownload(url, headers) { _, _ -> } }
            withTimeout(10_000) {
                while (store.journal?.completed?.containsKey(0) != true) delay(10)
            }
            running.cancelAndJoin()
            assertTrue(store.journal!!.completed.containsKey(0))
            slow.set(false)
            requests.clear()
            assertTrue(transfer.tryDownload(url, headers) { _, _ -> })
            assertFalse("0-2097151" in requests)
            assertArrayEquals(payload, store.file.readBytes())
        }
    }
}

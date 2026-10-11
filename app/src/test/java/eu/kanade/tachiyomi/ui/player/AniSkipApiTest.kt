package eu.kanade.tachiyomi.ui.player

import eu.kanade.tachiyomi.animesource.model.ChapterType
import eu.kanade.tachiyomi.animesource.model.TimeStamp
import eu.kanade.tachiyomi.ui.player.utils.AniSkipApi
import eu.kanade.tachiyomi.ui.player.utils.ChapterUtils
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AniSkipApiTest {
    @Test
    fun `an ending that reaches the end of the video can actually be skipped`() {
        val chapters = ChapterUtils.mergeChapters(
            emptyList(),
            listOf(TimeStamp(1350.0, 1440.0, "Ending", ChapterType.Ending)),
            1440,
        )
        val index = chapters.indexOfLast { it.chapterType == ChapterType.Ending }
        assertEquals(1440f, ChapterUtils.skipTarget(chapters, index, 1400f, 1440f))
        assertEquals(1400f, ChapterUtils.skipTarget(chapters, index, 1400f, 0f))
        assertEquals(1400f, ChapterUtils.skipTarget(chapters, -1, 1400f, 1440f))
    }

    private fun stamp(type: String, start: Int, end: Int) =
        """{"skipType":"$type","interval":{"startTime":$start,"endTime":$end}}"""

    @Test
    fun `mixed endings and all known skip types survive decoding`() {
        val values = listOf("op", "ed", "mixed-op", "mixed-ed", "recap")
            .mapIndexed { index, type -> stamp(type, index * 100, index * 100 + 90) }
        val result = AniSkipApi.parseResult("""{"found":true,"results":[${values.joinToString()}]}""", 1440)!!
        assertEquals(5, result.size)
        assertEquals(ChapterType.Ending, result[3].type)
    }

    @Test
    fun `unknown or invalid segments cannot discard a valid opening`() {
        val values = listOf(
            stamp("future-type", 10, 20),
            stamp("ed", 1200, 1500),
            stamp("op", -1, 90),
            stamp("op", 90, 30),
            stamp("op", 0, 90),
            stamp("op", 0, 90),
        )
        val result = AniSkipApi.parseResult("""{"found":true,"results":[${values.joinToString()}]}""", 1440)!!
        assertEquals(1, result.size)
        assertEquals(ChapterType.Opening, result.single().type)
    }

    @Test
    fun `no timestamps is a normal response`() {
        assertNull(AniSkipApi.parseResult("""{"found":false}""", 1440))
        assertNull(AniSkipApi.parseResult("""{"found":true,"results":null}""", 1440))
    }

    @Test
    fun `fractional episode numbers and requested types reach the API intact`() = runTest {
        val request = slot<Request>()
        val api = AniSkipApi(responseFactory("""{"found":false}""") { request.captured = it })
        assertNull(api.getResult(1, 1.5, 1440))
        assertEquals("/v2/skip-times/1/1.5", request.captured.url.encodedPath)
        assertEquals(
            listOf("op", "ed", "mixed-op", "mixed-ed", "recap"),
            request.captured.url.queryParameterValues("types[]"),
        )
        assertEquals("1440", request.captured.url.queryParameter("episodeLength"))
    }

    @Test
    fun `AniList mapping accepts whitespace and missing identifiers safely`() = runTest {
        val valid = AniSkipApi(responseFactory("""{ "data": { "Media": { "idMal": 42 } } }"""))
        assertEquals(42L, valid.getMalIdFromAL(1))
        val missing = AniSkipApi(responseFactory("""{"data":{"Media":{"idMal":null}}}"""))
        assertNull(missing.getMalIdFromAL(1))
    }

    @Test
    fun `cancelling an episode request cancels its HTTP call`() = runTest {
        val started = CompletableDeferred<Unit>()
        val call = mockk<Call>(relaxed = true)
        every { call.enqueue(any()) } answers {
            started.complete(Unit)
        }
        val factory = mockk<Call.Factory>()
        every { factory.newCall(any()) } returns call
        val request = launch { AniSkipApi(factory).getResult(1, 1.0, 1440) }
        started.await()
        request.cancelAndJoin()
        verify(exactly = 1) { call.cancel() }
    }

    private fun responseFactory(body: String, inspect: (Request) -> Unit = {}): Call.Factory {
        val factory = mockk<Call.Factory>()
        every { factory.newCall(any()) } answers {
            val request = firstArg<Request>()
            inspect(request)
            val call = mockk<Call>(relaxed = true)
            every { call.enqueue(any()) } answers {
                firstArg<Callback>().onResponse(
                    call,
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                        .message("OK").body(body.toResponseBody()).build(),
                )
            }
            call
        }
        return factory
    }
}

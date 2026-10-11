package eu.kanade.tachiyomi.data.updater

import eu.kanade.tachiyomi.network.NetworkHelper
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.release.GitHubAsset
import tachiyomi.data.release.GithubRelease
import tachiyomi.data.release.ReleaseServiceImpl
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.UpdateChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ReleaseServiceImplTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val clients = mutableListOf<OkHttpClient>()
    private val calls = mutableListOf<String>()

    private fun release(version: String) = GithubRelease(
        "v$version",
        "Fixture notes",
        "https://github.com/owouwuiwi/nyanime/releases/tag/v$version",
        listOf(GitHubAsset("Nyanime-Production-$version-arm64-v8a.apk", "https://example.test/$version.apk")),
    )

    private fun arguments(
        complete: Boolean = false,
        channel: UpdateChannel = UpdateChannel.RECOMMENDED,
    ) = GetApplicationRelease.Arguments(
        isPreview = false,
        commitCount = 1,
        versionName = "1.0.0.0",
        repository = "owouwuiwi/nyanime",
        assetPrefix = "Nyanime-Production",
        channel = channel,
        requireCompletePackage = complete,
    )

    private fun service(respond: (Request) -> Pair<Int, String>): ReleaseServiceImpl {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            calls += request.url.toString()
            val (code, body) = respond(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(code).message("Fixture").body(body.toResponseBody()).build()
        }.build()
        clients += client
        val network = mockk<NetworkHelper>()
        every { network.apiClient } returns client
        return ReleaseServiceImpl(network, json, listOf("arm64-v8a"))
    }

    @AfterEach
    fun close() {
        clients.forEach {
            it.dispatcher.cancelAll()
            it.connectionPool.evictAll()
            it.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun `migration uses the recommended complete latest without requesting a patch feed`() = runTest {
        val service = service {
            assertTrue(it.url.encodedPath.endsWith("/latest"))
            200 to json.encodeToString(release("1.0.0.1"))
        }
        assertEquals("https://example.test/1.0.0.1.apk", service.latest(arguments(complete = true))!!.downloadLink)
        assertEquals(1, calls.size)
    }

    @Test
    fun `complete preview lookup does not stop after the first page with an older APK`() = runTest {
        val service = service {
            when (it.url.queryParameter("page")) {
                "1" -> 200 to json.encodeToString(List(100) { release("1.0.0.1") })
                "2" -> 200 to json.encodeToString(listOf(release("1.0.0.2")))
                else -> error("Unexpected fixture request")
            }
        }
        assertEquals(
            "1.0.0.2",
            service.latest(arguments(complete = true, channel = UpdateChannel.INCLUDING_PREVIEWS))!!.version,
        )
        assertEquals(2, calls.size)
    }

    @Test
    fun `a newer complete latest remains usable when its release feed fails`() = runTest {
        val service = service {
            if (it.url.encodedPath.endsWith("/latest")) {
                200 to json.encodeToString(release("1.0.0.1"))
            } else {
                503 to "Temporary fixture failure"
            }
        }
        assertEquals("1.0.0.1", service.latest(arguments())!!.version)
        assertEquals(2, calls.size)
    }

    @Test
    fun `failed feed with no newer known candidate remains an error instead of starting a cooldown`() {
        val service = service {
            if (it.url.encodedPath.endsWith("/latest")) {
                200 to json.encodeToString(release("1.0.0.0"))
            } else {
                503 to "Temporary fixture failure"
            }
        }
        assertThrows(Exception::class.java) { runTest { service.latest(arguments()) } }
    }

    @Test
    fun `cancelling the feed cannot return an earlier candidate as a successful update`() = runTest {
        val reached = CompletableDeferred<Unit>()
        val releaseFeed = CountDownLatch(1)
        val service = service {
            if (it.url.encodedPath.endsWith("/latest")) {
                200 to json.encodeToString(release("1.0.0.1"))
            } else {
                reached.complete(Unit)
                releaseFeed.await(5, TimeUnit.SECONDS)
                503 to "Cancelled fixture response"
            }
        }
        var returned = false
        val job = launch {
            service.latest(arguments())
            returned = true
        }
        try {
            reached.await()
            job.cancelAndJoin()
            assertTrue(!returned)
        } finally {
            releaseFeed.countDown()
        }
    }
}

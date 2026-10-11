package it.lorelore.nyanime.update

import eu.kanade.tachiyomi.data.updater.GITHUB_REPO
import eu.kanade.tachiyomi.data.updater.UpdateScreenPhase
import eu.kanade.tachiyomi.data.updater.delta.DeltaPatch
import eu.kanade.tachiyomi.data.updater.delta.DeltaUpdateEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.GZIPOutputStream

class ReleaseGraphClientTest {
    @TempDir lateinit var directory: File
    private val requests = mutableListOf<String>()
    private val replies = mutableMapOf<String, ByteArray>()
    private val checked = mutableListOf<Long>()
    private val installed by lazy { File(directory, "installed.apk").apply { writeBytes(ByteArray(4000) { 1 }) } }
    private var freeSpace = Long.MAX_VALUE
    private var split = false
    private var rejectCode: Long? = null
    private val environment = object : DeltaUpdateEnvironment {
        override val packageName = "it.owouwuiwi.nyanime"
        override val installedApk get() = installed
        override val installedVersionCode = 1L
        override val splitInstallation get() = split
        override fun availableSpace(directory: File) = freeSpace
        override fun validate(file: File, versionCode: Long, versionName: String) {
            require(versionCode > installedVersionCode)
            validateReference(file, versionCode, versionName)
        }
        override fun validateReference(file: File, versionCode: Long, versionName: String) {
            require(versionCode != rejectCode) { "Rejected APK signature" }
            checked += versionCode
        }
    }

    private fun node(code: Long, bytes: ByteArray, complete: Boolean): ReleaseGraph.Package {
        val version = "1.0.0.$code"
        val url = ReleaseGraph.assetUrl(GITHUB_REPO, version, ReleaseGraph.apkName(version, "arm64-v8a"))
        val file = File(directory, "fixture-$code").apply { writeBytes(bytes) }
        if (complete) replies[url] = bytes
        return ReleaseGraph.Package(
            DeltaPatch.hash(file),
            bytes.size.toLong(),
            version,
            code,
            "arm64-v8a",
            if (complete) url else null,
        )
    }

    private fun edge(from: ReleaseGraph.Package, to: ReleaseGraph.Package): ReleaseGraph.Patch {
        val bytes = File(directory, "fixture-${to.versionCode}").readBytes()
        val raw = ByteArrayOutputStream()
        DataOutputStream(GZIPOutputStream(raw)).use { output ->
            output.writeBytes("NYADLT01")
            output.writeLong(from.size)
            output.writeLong(to.size)
            listOf(from.sha256, to.sha256).forEach { hash ->
                output.write(hash.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            }
            output.writeInt(1)
            output.writeByte(1)
            output.writeLong(to.size)
            output.write(bytes)
        }
        val patch = File(directory, "fixture-patch-${from.versionCode}-${to.versionCode}").apply {
            writeBytes(raw.toByteArray())
        }
        val url = ReleaseGraph.assetUrl(
            GITHUB_REPO,
            to.versionName,
            ReleaseGraph.patchName(from.sha256, to.versionName, to.abi),
        )
        replies[url] = patch.readBytes()
        return ReleaseGraph.Patch(from.sha256, to.sha256, DeltaPatch.hash(patch), patch.length(), url)
    }

    private fun fixture(completeTarget: Boolean = false): Pair<ReleaseGraph, String> {
        val a = node(1, installed.readBytes(), true)
        val b = node(2, ByteArray(4000) { 2 }, false)
        val c = node(3, ByteArray(4000) { 3 }, completeTarget)
        val graph = ReleaseGraph(
            2,
            environment.packageName,
            c.versionName,
            c.versionCode,
            listOf(c.sha256),
            listOf(a, b, c),
            listOf(edge(a, b), edge(b, c)),
        )
        val url = ReleaseGraph.assetUrl(GITHUB_REPO, c.versionName, ReleaseGraph.FILE_NAME)
        replies[url] = Json.encodeToString(graph).toByteArray()
        return graph to url
    }

    private fun client(): ReleaseGraphClient {
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            requests += url
            val bytes = replies[url]
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (bytes == null) 404 else 200).message("Fixture")
                .body((bytes ?: byteArrayOf()).toResponseBody()).build()
        }.build()
        return ReleaseGraphClient(environment, transport, listOf("arm64-v8a"))
    }

    @Test
    fun `two skipped releases reconstruct only the final package and verify every checkpoint`() = runBlocking {
        val (graph, url) = fixture()
        val result = client().prepare(url, directory, "chain", {}, {})
        assertTrue(result.readBytes().contentEquals(ByteArray(4000) { 3 }))
        assertTrue(checked.containsAll(listOf(2, 3)))
        assertEquals(listOf(url) + graph.patches.map { it.url }, requests)
        assertFalse(File(directory, "route-chain").exists())
        assertTrue(installed.readBytes().contentEquals(ByteArray(4000) { 1 }))
    }

    @Test
    fun `split installation uses complete base rather than hashing an incomplete installed APK`() = runBlocking {
        val (graph, url) = fixture()
        split = true
        client().prepare(url, directory, "split", {}, {})
        assertTrue(graph.packages.first().url in requests)
        assertTrue(checked.contains(1L))
    }

    @Test
    fun `corrupt patch falls back to an advertised full target with exact hash`() = runBlocking {
        val (graph, url) = fixture(true)
        replies[graph.patches.first().url] = ByteArray(graph.patches.first().size.toInt())
        client().prepare(url, directory, "fallback", {}, {})
        assertTrue(graph.packages.last().url in requests)
    }

    @Test
    fun `missing patch does not invent a full target URL`() {
        val (graph, url) = fixture()
        replies.remove(graph.patches.last().url)
        assertThrows(IOException::class.java) { runBlocking { client().prepare(url, directory, "missing", {}, {}) } }
        val nonexistent = ReleaseGraph.assetUrl(
            GITHUB_REPO,
            graph.versionName,
            ReleaseGraph.apkName(graph.versionName, "arm64-v8a"),
        )
        assertFalse(nonexistent in requests)
    }

    @Test
    fun `worker restart resumes from verified checkpoint but rejects a poisoned cache`() = runBlocking {
        val (graph, url) = fixture()
        val job = File(directory, "route-resume").apply { mkdirs() }
        val middle = graph.packages[1]
        File(job, middle.sha256).writeBytes(ByteArray(4000) { 2 })
        client().prepare(url, directory, "resume", {}, {})
        assertFalse(graph.patches.first().url in requests)
        requests.clear()
        job.mkdirs()
        File(job, middle.sha256).writeBytes(ByteArray(4000) { 9 })
        client().prepare(url, directory, "resume", {}, {})
        assertTrue(graph.patches.first().url in requests)
    }

    @Test
    fun `signature failures and insufficient storage never return an installable update`() {
        val (_, url) = fixture()
        rejectCode = 3
        assertThrows(IOException::class.java) { runBlocking { client().prepare(url, directory, "signer", {}, {}) } }
        assertFalse(File(directory, "update-signer.rebuilt").exists())
        requests.clear()
        rejectCode = null
        freeSpace = 0
        assertThrows(IOException::class.java) { runBlocking { client().prepare(url, directory, "space", {}, {}) } }
        assertEquals(listOf(url), requests)
    }

    @Test
    fun `cancellation cleans only the owning job and cannot trigger fallback`() {
        val (_, url) = fixture()
        val other = File(directory, "route-other").apply { mkdirs() }
        File(other, "keep").writeText("another update")
        assertThrows(CancellationException::class.java) {
            runBlocking {
                client().prepare(url, directory, "cancel", {
                    if (it ==
                        UpdateScreenPhase.REBUILDING
                    ) {
                        throw CancellationException()
                    }
                }, {})
            }
        }
        assertEquals(2, requests.size)
        assertFalse(File(directory, "route-cancel").exists())
        assertTrue(File(other, "keep").exists())
    }
}

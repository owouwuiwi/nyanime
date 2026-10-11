package eu.kanade.tachiyomi.data.updater

import eu.kanade.tachiyomi.data.updater.delta.DeltaManifest
import eu.kanade.tachiyomi.data.updater.delta.DeltaPatch
import eu.kanade.tachiyomi.data.updater.delta.DeltaUpdateClient
import eu.kanade.tachiyomi.data.updater.delta.DeltaUpdateEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

class DeltaUpdateClientTest {
    @TempDir lateinit var directory: File
    private val targetName = "Nyanime-Production-1.2.3.4-arm64-v8a.apk"
    private val releaseUrl = "https://github.com/owouwuiwi/nyanime/releases/download/v1.2.3.4/"
    private val requests = mutableListOf<String>()

    private inner class Environment : DeltaUpdateEnvironment {
        override val packageName = "it.owouwuiwi.nyanime"
        override val installedApk = File(directory, "installed.apk").apply {
            writeBytes(
                ByteArray(2 * 1024 * 1024) {
                    (
                        it %
                            251
                        ).toByte()
                },
            )
        }
        override val installedVersionCode = 200L
        override var splitInstallation = false
        var space = Long.MAX_VALUE
        var rejectSignature = false
        var verified = 0
        override fun availableSpace(directory: File) = space
        override fun validate(file: File, versionCode: Long, versionName: String) {
            check(!rejectSignature) { "Invalid APK signature" }
            assertEquals(201, versionCode.toInt())
            assertEquals("1.2.3.4", versionName)
            verified++
        }
    }

    private data class Fixture(val patch: ByteArray, val patchName: String, val manifest: String, val target: ByteArray)

    private fun fixture(environment: Environment): Fixture {
        val base = environment.installedApk.readBytes()
        val suffix = "new signed target bytes".toByteArray()
        val target = base + suffix
        val hash = DeltaPatch.hash(environment.installedApk)
        val targetFile = File(directory, "fixture-target").apply { writeBytes(target) }
        val raw = ByteArrayOutputStream()
        DataOutputStream(GZIPOutputStream(raw)).use {
            it.writeBytes("NYADLT01")
            it.writeLong(base.size.toLong())
            it.writeLong(target.size.toLong())
            it.write(hash.chunked(2).map { byte -> byte.toInt(16).toByte() }.toByteArray())
            it.write(DeltaPatch.hash(targetFile).chunked(2).map { byte -> byte.toInt(16).toByte() }.toByteArray())
            it.writeInt(2)
            it.writeByte(0)
            it.writeLong(0)
            it.writeLong(base.size.toLong())
            it.writeByte(1)
            it.writeLong(suffix.size.toLong())
            it.write(suffix)
        }
        val patch = raw.toByteArray()
        val patchFile = File(directory, "fixture-patch").apply { writeBytes(patch) }
        val name = "Nyanime-Delta-${hash.take(16)}-1.2.3.4-arm64-v8a.nyadelta"
        val manifest = """{"schema":1,"applicationId":"it.owouwuiwi.nyanime","versionName":"1.2.3.4","versionCode":201,"patches":[
            {"baseSha256":"$hash","baseSize":${base.size},"targetSha256":"${DeltaPatch.hash(
            targetFile,
        )}","targetSize":${target.size},
            "patchSha256":"${DeltaPatch.hash(
            patchFile,
        )}","patchSize":${patch.size},"patch":"$name","target":"$targetName"}]}"""
        return Fixture(patch, name, manifest, target)
    }

    private fun client(environment: Environment, reply: (String) -> Pair<Int, ByteArray>): DeltaUpdateClient {
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request.url.toString()
            val (status, bytes) = reply(request.url.toString())
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
                .body(bytes.toResponseBody()).build()
        }.build()
        return DeltaUpdateClient(environment, transport)
    }

    @Test
    fun `complete acquisition verifies final APK and cleans patch before returning`() = runBlocking {
        val environment = Environment()
        val fixture = fixture(environment)
        val phases = mutableListOf<UpdateScreenPhase>()
        val client = client(environment) { url ->
            200 to
                if (url.endsWith(DeltaManifest.FILE_NAME)) fixture.manifest.toByteArray() else fixture.patch
        }
        val result = client.prepare(releaseUrl + targetName, directory, "success", { phases += it }, {})
        assertNotNull(result)
        assertTrue(result!!.readBytes().contentEquals(fixture.target))
        assertEquals(1, environment.verified)
        assertEquals(
            listOf(
                UpdateScreenPhase.PREPARING,
                UpdateScreenPhase.DOWNLOADING_DELTA,
                UpdateScreenPhase.REBUILDING,
                UpdateScreenPhase.VERIFYING,
            ),
            phases,
        )
        assertFalse(File(directory, "update-success.nyadelta").exists())
    }

    @Test
    fun `missing metadata malformed metadata and failed patch return control to full downloader`() = runBlocking {
        val environment = Environment()
        val fixture = fixture(environment)
        for (mode in listOf("missing", "invalid-json", "truncated", "signature")) {
            environment.rejectSignature = mode == "signature"
            val client = client(environment) { url ->
                if (url.endsWith(DeltaManifest.FILE_NAME)) {
                    when (mode) {
                        "missing" -> 404 to byteArrayOf()
                        "invalid-json" -> 200 to "{broken".toByteArray()
                        else -> 200 to fixture.manifest.toByteArray()
                    }
                } else {
                    200 to if (mode == "truncated") fixture.patch.copyOf(10) else fixture.patch
                }
            }
            assertNull(client.prepare(releaseUrl + targetName, directory, mode, {}, {}))
            assertFalse(File(directory, "update-$mode.rebuilt").exists())
            assertFalse(File(directory, "update-$mode.nyadelta").exists())
        }
    }

    @Test
    fun `space check prevents patch transfer and split installations skip metadata entirely`() = runBlocking {
        val environment = Environment()
        val fixture = fixture(environment)
        val client = client(environment) { 200 to fixture.manifest.toByteArray() }
        environment.space = 0
        assertNull(client.prepare(releaseUrl + targetName, directory, "space", {}, {}))
        assertEquals(listOf(releaseUrl + DeltaManifest.FILE_NAME), requests)
        requests.clear()
        environment.splitInstallation = true
        assertNull(client.prepare(releaseUrl + targetName, directory, "split", {}, {}))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `cancellation propagates instead of starting a fallback download`() {
        val environment = Environment()
        val fixture = fixture(environment)
        val client = client(environment) { url ->
            200 to
                if (url.endsWith(DeltaManifest.FILE_NAME)) fixture.manifest.toByteArray() else fixture.patch
        }
        assertThrows(CancellationException::class.java) {
            runBlocking {
                client.prepare(releaseUrl + targetName, directory, "cancel", { phase ->
                    if (phase == UpdateScreenPhase.REBUILDING) throw CancellationException("User cancelled")
                }, {})
            }
        }
        assertFalse(File(directory, "update-cancel.rebuilt").exists())
        assertFalse(File(directory, "update-cancel.nyadelta").exists())
        assertEquals(2, requests.size)
    }
}

package eu.kanade.tachiyomi.data.updater

import eu.kanade.tachiyomi.data.updater.delta.DeltaManifest
import eu.kanade.tachiyomi.data.updater.delta.DeltaPatch
import eu.kanade.tachiyomi.data.updater.delta.DeltaRecord
import eu.kanade.tachiyomi.data.updater.delta.DeltaReleaseLocation
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
import java.util.Base64
import java.util.zip.GZIPOutputStream

class DeltaUpdateTest {
    @TempDir lateinit var directory: File

    private val fixture by lazy {
        Json.parseToJsonElement(
            requireNotNull(javaClass.getResource("/updater/delta-round-trip.json")).readText(),
        ).jsonObject
    }

    private fun file(key: String) = File(directory, key).apply {
        writeBytes(Base64.getDecoder().decode(fixture.getValue(key).jsonPrimitive.content))
    }

    private fun record(): DeltaRecord {
        val record = fixture.getValue("record").jsonObject
        fun text(key: String) = record.getValue(key).jsonPrimitive.content
        return DeltaRecord(
            text("baseSha256"),
            text("baseSize").toLong(),
            text("targetSha256"),
            text("targetSize").toLong(),
            text("patchSha256"),
            text("patchSize").toLong(),
            "fixture.nyadelta",
            "fixture.apk",
        )
    }

    @Test
    fun `python generated ZIP patch reconstructs exact target bytes independently in Kotlin`() {
        val target = file("target")
        val result = File(directory, "output.apk")
        val progress = mutableListOf<Long>()
        DeltaPatch.apply(file("base"), file("patch"), result, record(), progress = { written, _ ->
            progress.add(written)
        })
        assertTrue(target.readBytes().contentEquals(result.readBytes()))
        assertEquals(record().targetSha256, DeltaPatch.hash(result))
        assertEquals(record().targetSize, progress.last())
    }

    @Test
    fun `wrong base hash or modified patch fails without leaving an output`() {
        val base = file("base")
        val patch = file("patch")
        val result = File(directory, "output.apk")
        base.writeBytes(base.readBytes().apply { this[100] = (this[100].toInt() xor 1).toByte() })
        assertThrows(IllegalArgumentException::class.java) { DeltaPatch.apply(base, patch, result, record()) }
        assertFalse(result.exists())
        file("base")
        patch.appendBytes(byteArrayOf(0))
        assertThrows(IllegalArgumentException::class.java) { DeltaPatch.apply(base, patch, result, record()) }
        assertFalse(result.exists())
    }

    @Test
    fun `cancelling after output starts removes the partial reconstruction and keeps base intact`() {
        val base = file("base")
        val result = File(directory, "output.apk")
        var cancelled = false
        assertThrows(CancellationException::class.java) {
            DeltaPatch.apply(base, file("patch"), result, record(), checkActive = {
                if (cancelled) throw CancellationException("Cancelled by test")
            }, progress = { _, _ -> cancelled = true })
        }
        assertFalse(result.exists())
        assertEquals(record().baseSha256, DeltaPatch.hash(base))
    }

    @Test
    fun `oversized negative and unknown commands are rejected before excessive output`() {
        val base = file("base")
        val expected = record()
        val commands = listOf<(DataOutputStream) -> Unit>(
            { it.writeByte(2) },
            {
                it.writeByte(0)
                it.writeLong(-1)
                it.writeLong(1)
            },
            {
                it.writeByte(0)
                it.writeLong(0)
                it.writeLong(Long.MAX_VALUE)
            },
            {
                it.writeByte(1)
                it.writeLong(expected.targetSize + 1)
            },
            {
                it.writeByte(1)
                it.writeLong(-1)
            },
            {
                it.writeByte(1)
                it.writeLong(0)
            },
        )
        for (command in commands) {
            val raw = ByteArrayOutputStream()
            DataOutputStream(GZIPOutputStream(raw)).use { output ->
                output.writeBytes("NYADLT01")
                output.writeLong(expected.baseSize)
                output.writeLong(expected.targetSize)
                output.write(expected.baseSha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
                output.write(expected.targetSha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
                output.writeInt(1)
                command(output)
            }
            val patch = File(directory, "hostile.patch").apply { writeBytes(raw.toByteArray()) }
            val result = File(directory, "output.apk")
            assertThrows(Exception::class.java) {
                DeltaPatch.apply(
                    base,
                    patch,
                    result,
                    expected.copy(patchSize = patch.length(), patchSha256 = DeltaPatch.hash(patch)),
                )
            }
            assertFalse(result.exists())
        }
    }

    private fun manifest(baseHash: String = "a".repeat(64), patch: String? = null) = buildJsonObject {
        put("schema", 1)
        put("applicationId", "it.owouwuiwi.nyanime")
        put("versionName", "1.2.3.4")
        put("versionCode", 201)
        put(
            "patches",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("baseSha256", baseHash)
                        put("baseSize", 1_000_000)
                        put("targetSha256", "b".repeat(64))
                        put("targetSize", 4_000_000)
                        put("patchSha256", "c".repeat(64))
                        put("patchSize", 500_000)
                        put("patch", patch ?: "Nyanime-Delta-${baseHash.take(16)}-1.2.3.4-arm64-v8a.nyadelta")
                        put("target", "Nyanime-Production-1.2.3.4-arm64-v8a.apk")
                    },
                )
            },
        )
    }.toString()

    @Test
    fun `manifest uses exact APK hash size and target and requires worthwhile savings`() {
        val name = "Nyanime-Production-1.2.3.4-arm64-v8a.apk"
        val parsed = DeltaManifest.parse(manifest(), "it.owouwuiwi.nyanime", name)
        assertNotNull(parsed.select(name, 1_000_000, "a".repeat(64)))
        assertNull(parsed.select(name, 1_000_001, "a".repeat(64)))
        assertNull(parsed.select(name, 1_000_000, "d".repeat(64)))
        assertNull(parsed.select(name.replace("arm64-v8a", "universal"), 1_000_000, "a".repeat(64)))
        assertThrows(Exception::class.java) {
            DeltaManifest.parse(manifest(patch = "../bad.patch"), "it.owouwuiwi.nyanime", name)
        }
        assertThrows(Exception::class.java) { DeltaManifest.parse(manifest(), "wrong.package", name) }
        assertThrows(Exception::class.java) {
            DeltaManifest.parse(manifest().replace("500000", "3900000"), "it.owouwuiwi.nyanime", name)
        }
        assertThrows(Exception::class.java) { DeltaManifest.parse("[".repeat(10000), "it.owouwuiwi.nyanime", name) }
    }

    @Test
    fun `delta discovery accepts only exact ordinary immutable release assets`() {
        val path = "https://github.com/owouwuiwi/nyanime/releases/download/v1.2.3.4/" +
            "Nyanime-Production-1.2.3.4-arm64-v8a.apk"
        assertNotNull(DeltaReleaseLocation.from(path))
        for (url in listOf(
            path.replace("https:", "http:"), path + "?redirect=1", path + "#part",
            path.replace("github.com", "github.com.example.invalid"), path.replace("github.com", "user@github.com"),
            path.replace("v1.2.3.4/", "latest/"), path.replace("Production-1.2.3.4", "Production-1.2.3.5"),
            path.replace(".apk", ".nyadelta"), path.replace("/nyanime/", "/other/"),
        )) {
            assertNull(DeltaReleaseLocation.from(url), url)
        }
    }

    @Test
    fun `worker phase remains busy cancellable and progress aware during reconstruction`() {
        for (phase in listOf(
            UpdateScreenPhase.PREPARING,
            UpdateScreenPhase.DOWNLOADING_DELTA,
            UpdateScreenPhase.REBUILDING,
            UpdateScreenPhase.DOWNLOADING_FULL,
            UpdateScreenPhase.VERIFYING,
        )) {
            assertEquals(phase, updateScreenPhase(androidx.work.WorkInfo.State.RUNNING, false, false, phase.name))
            assertTrue(phase.busy)
            assertEquals(phase != UpdateScreenPhase.VERIFYING, phase.cancellable)
        }
        assertFalse(UpdateScreenPhase.PREPARING.hasProgress)
        assertTrue(UpdateScreenPhase.REBUILDING.hasProgress)
        assertEquals(
            UpdateScreenPhase.DOWNLOADING,
            updateScreenPhase(androidx.work.WorkInfo.State.RUNNING, false, false, "READY"),
        )
    }
}

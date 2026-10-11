package eu.kanade.tachiyomi.data.migration

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.spec.SecretKeySpec

class MigrationArchiveTest {
    @TempDir lateinit var root: java.nio.file.Path
    private fun newFolder() = java.nio.file.Files.createTempDirectory(root, "fixture").toFile()

    @Test fun preservesOpaqueReferencesAndExactProgressFiles() {
        val original = newFolder()
        original.resolve("library.sqlite").writeBytes(byteArrayOf(1, 2, 3, 0, -1))
        original.resolve(
            "resume.json",
        ).writeText("""{"source":42,"entry":"/item/opaque","position":17,"completed":false}""")
        val output = ByteArrayOutputStream()
        val expected = MigrationArchive.write(mapOf("databases" to original), output, "test.previous", 216, null)
        val destination = newFolder()
        assertEquals(
            expected,
            MigrationArchive.extract(ByteArrayInputStream(output.toByteArray()), destination, expected.bytes),
        )
        assertEquals(
            original.resolve("resume.json").readText(),
            destination.resolve("databases/resume.json").readText(),
        )
    }

    @Test fun rejectsTraversalUnknownRootsAndExpansionBeyondBudget() {
        listOf(
            "files/../secret",
            "/files/data",
            "files//data",
            "cache/file",
            "files/./data",
            "files/data:stream",
        ).forEach {
            assertFalse(MigrationArchive.safePath(it))
        }
        val original = newFolder().apply { resolve("data").writeBytes(ByteArray(4096)) }
        val output = ByteArrayOutputStream()
        MigrationArchive.write(mapOf("files" to original), output, "test.previous", 216, null)
        assertThrows(IllegalArgumentException::class.java) {
            MigrationArchive.extract(ByteArrayInputStream(output.toByteArray()), newFolder(), 4095)
        }
    }

    @Test fun rejectsManifestThatDoesNotDescribeActualBytes() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use {
            it.putNextEntry(ZipEntry("files/data"))
            it.write(byteArrayOf(1))
            it.closeEntry()
            it.putNextEntry(ZipEntry("manifest.json"))
            it.write(
                Json.encodeToString(
                    MigrationManifest(
                        packageName = "test.previous",
                        versionCode = 216,
                        storageUri = null,
                        files = emptyList(),
                    ),
                ).toByteArray(),
            )
            it.closeEntry()
        }
        assertThrows(IllegalArgumentException::class.java) {
            MigrationArchive.extract(ByteArrayInputStream(output.toByteArray()), newFolder(), 1)
        }
    }

    @Test fun authenticatedFramesRejectCorruptionTruncationAndTrailingData() {
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val original = ByteArray(700_000) { (it % 127).toByte() }
        val output = ByteArrayOutputStream()
        MigrationCrypto.encrypt(output, key).use { it.write(original) }
        val bytes = output.toByteArray()
        assertEquals(
            original.toList(),
            MigrationCrypto.decrypt(ByteArrayInputStream(bytes), key).use {
                it.readBytes().toList()
            },
        )
        for (damaged in listOf(
            bytes.copyOf(bytes.size - 1),
            bytes + byteArrayOf(1),
            bytes.copyOf().apply {
                this[70] =
                    (this[70].toInt() xor 1).toByte()
            },
        )) {
            assertThrows(Exception::class.java) {
                MigrationCrypto.decrypt(ByteArrayInputStream(damaged), key).use { it.readBytes() }
            }
        }
    }
}

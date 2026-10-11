package eu.kanade.tachiyomi.data.cloud.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CloudSyncDocumentFilesTest {
    @TempDir lateinit var root: File
    private val id = "b41fca19-82ab-46c4-9cbf-12515553be49"

    private fun file(path: String) = File(root, path).apply {
        parentFile!!.mkdirs()
        writeText("encrypted")
    }

    @Test
    fun ownUploadedDocumentIsReadableBeforeItsHistoryIsStaged() {
        val original = file("cloud-sync-outbox/$id.nysync")
        assertEquals(
            original.canonicalFile,
            CloudSyncDocumentFiles(root).verified(original.path, id, original.length()),
        )
    }

    @Test
    fun downloadedDocumentStillUsesTheBoundedNativeCache() {
        val downloaded = file("telegram/files/documents/remote.nysync")
        assertEquals(
            downloaded.canonicalFile,
            CloudSyncDocumentFiles(root).verified(downloaded.path, id, downloaded.length()),
        )
    }

    @Test
    fun anotherPacketPrivateFileOrDirectoryWithSimilarPrefixIsNeverRead() {
        for (path in listOf(
            "cloud-sync-outbox/other.nysync",
            "cloud/keys",
            "telegram-other/private",
            "cloud-sync-outbox/../private",
        )) {
            val unsafe = file(path)
            assertThrows(IllegalArgumentException::class.java) {
                CloudSyncDocumentFiles(root).verified(unsafe.path, id, unsafe.length())
            }
        }
    }

    @Test
    fun missingTruncatedAndNonFilePathsAreNotAccepted() {
        val original = file("cloud-sync-outbox/$id.nysync")
        assertThrows(IllegalArgumentException::class.java) {
            CloudSyncDocumentFiles(root).verified(original.path, id, original.length() + 1)
        }
        original.delete()
        assertThrows(IllegalArgumentException::class.java) {
            CloudSyncDocumentFiles(root).verified(original.path, id, 9)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CloudSyncDocumentFiles(root).verified(File(root, "telegram").apply { mkdirs() }.path, id, 9)
        }
    }
}

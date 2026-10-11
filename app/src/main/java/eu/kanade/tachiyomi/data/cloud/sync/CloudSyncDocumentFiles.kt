package eu.kanade.tachiyomi.data.cloud.sync

import java.io.File
import java.util.UUID

/**
 * TDLib may return either its downloaded cache or the original uploaded document.
 * Only that packet's own encrypted outbox file is permitted outside the TDLib directory.
 */
internal class CloudSyncDocumentFiles(private val noBackup: File) {
    fun verified(path: String, id: String, bytes: Long): File {
        require(UUID.fromString(id).toString() == id && bytes > 0)
        val base = noBackup.canonicalFile
        val downloaded = File(path).canonicalFile
        val telegram = File(base, "telegram").path + File.separator
        val original = File(base, "cloud-sync-outbox/$id.nysync")
        require(downloaded.path.startsWith(telegram) || downloaded == original) {
            "Unexpected synchronization storage"
        }
        require(downloaded.isFile && downloaded.canRead() && downloaded.length() == bytes) {
            "Synchronization document is incomplete"
        }
        return downloaded
    }
}

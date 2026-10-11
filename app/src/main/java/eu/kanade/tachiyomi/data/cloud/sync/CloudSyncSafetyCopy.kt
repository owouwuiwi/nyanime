package eu.kanade.tachiyomi.data.cloud.sync

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import nyanime.telegram.sync.SyncBackupCipher
import java.io.File
import java.util.UUID

/** A full local safety copy must succeed before the first merge. It is never uploaded as sync data. */
internal class CloudSyncSafetyCopy(private val context: Context) {
    suspend fun create(keys: CloudSyncKeys) {
        val directory = File(context.noBackupFilesDir, "cloud-sync-safety/${keys.account}/${keys.space}")
        check(directory.exists() || directory.mkdirs())
        val complete = File(directory, "before-merge.nyabks")
        if (complete.exists()) {
            // Never trust mere file existence after an interrupted first setup.
            decrypt(
                keys,
                object : java.io.OutputStream() {
                    override fun write(value: Int) = Unit
                    override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
                },
            )
            return
        }
        val clear = File(directory, "before-merge.partial.nyabk")
        val target = AtomicFile(complete)
        try {
            BackupCreator(context, isAutoBackup = false).backup(Uri.fromFile(clear), BackupOptions.complete())
            check(clear.isFile && clear.length() > 0)
            val key = keys.key()
            val output = target.startWrite()
            try {
                // Streaming encryption closes its stream; shield the AtomicFile descriptor until fsync.
                val shield = object : java.io.FilterOutputStream(output) {
                    override fun close() {
                        flush()
                    }
                }
                clear.inputStream().use {
                    SyncBackupCipher.encrypt(
                        it,
                        shield,
                        clear.length(),
                        keys.account,
                        keys.space,
                        keys.epoch,
                        UUID.randomUUID().toString(),
                        key,
                    )
                }
                target.finishWrite(output)
            } catch (error: Exception) {
                target.failWrite(output)
                throw error
            } finally {
                key.fill(0)
            }
        } finally {
            clear.delete()
        }
    }

    fun exists(keys: CloudSyncKeys): Boolean = File(
        context.noBackupFilesDir,
        "cloud-sync-safety/${keys.account}/${keys.space}/before-merge.nyabks",
    ).isFile

    fun restorePreview(keys: CloudSyncKeys): File {
        val file = File(context.cacheDir, "cloud-safety-${UUID.randomUUID()}.nyabk")
        try {
            file.outputStream().use { decrypt(keys, it) }
            file.setLastModified(
                File(context.noBackupFilesDir, "cloud-sync-safety/${keys.account}/${keys.space}/before-merge.nyabks")
                    .lastModified(),
            )
            return file
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    private fun decrypt(keys: CloudSyncKeys, output: java.io.OutputStream) {
        val complete =
            File(context.noBackupFilesDir, "cloud-sync-safety/${keys.account}/${keys.space}/before-merge.nyabks")
        AtomicFile(complete).openRead().use { input ->
            val header = SyncBackupCipher.readHeader(input)
            require(header.protection.space == keys.space)
            val key = keys.key(header.protection.epoch)
            try {
                SyncBackupCipher.decrypt(input, output, keys.account, header, key)
            } finally {
                key.fill(0)
            }
        }
    }
}

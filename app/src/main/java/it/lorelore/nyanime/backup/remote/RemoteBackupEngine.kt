package it.lorelore.nyanime.backup.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.decodeFromJsonElement
import nyanime.telegram.sync.SyncBackupCipher
import java.io.File
import java.util.UUID

internal data class RemoteBackupEntry(val objectId: String, val commit: RemoteBackupCommit)

/** Full snapshots only. Never merges a remote snapshot over newer local data automatically. */
internal class RemoteBackupEngine(private val store: RemoteFileStore, private val directory: File) {
    suspend fun list(key: ByteArray): List<RemoteBackupEntry> {
        store.prepare()
        val objects = store.list().filter { it.name.endsWith(".json") && isRemoteBackupName(it.name) }
        require(objects.size <= 1000)
        return objects.mapNotNull { item ->
            currentCoroutineContext().ensureActive()
            val file = File.createTempFile("manifest-", ".tmp", directory)
            try {
                store.download(item.id, file, 16 * 1024)
                val commit = remoteBackupJson.decodeFromJsonElement<RemoteBackupCommit>(
                    boundedRemoteJson(file.readText()),
                )
                if (!commit.verify(key) || item.name != "nyanime-${commit.id}.json") {
                    null
                } else {
                    RemoteBackupEntry(item.id, commit)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: RemoteStorageException) {
                if (failure.reason != "missing") throw failure
                null
            } catch (_: IllegalArgumentException) {
                null
            } finally {
                file.delete()
            }
        }.sortedByDescending { it.commit.created }
    }

    suspend fun upload(
        snapshot: File,
        encrypted: File,
        id: String,
        device: String,
        label: String,
        created: Long,
        key: ByteArray,
        phase: suspend (String) -> Unit,
    ): RemoteBackupEntry {
        store.prepare()
        if (!encrypted.exists()) {
            phase("encrypting")
            val partial = File(encrypted.parentFile, "${encrypted.name}.partial")
            try {
                val caller = currentCoroutineContext()
                interruptibleFileInput(snapshot, caller).use { input ->
                    partial.outputStream().use { output ->
                        SyncBackupCipher.encrypt(input, output, snapshot.length(), CRYPTO_DOMAIN, device, 1, id, key)
                    }
                }
                check(partial.renameTo(encrypted))
            } finally {
                partial.delete()
            }
        }
        currentCoroutineContext().ensureActive()
        val digest = encrypted.remoteSha256()
        phase("uploading")
        val objectRef = store.upload("nyanime-$id.nyabk.enc", encrypted)
        val checkFile = File.createTempFile("verify-", ".tmp", directory)
        val manifest = File.createTempFile("commit-", ".tmp", directory)
        try {
            phase("verifying")
            store.download(objectRef.id, checkFile, encrypted.length())
            check(checkFile.length() == encrypted.length() && checkFile.remoteSha256() == digest)
            val commit = RemoteBackupCommit(
                id = id,
                device = device,
                label = label,
                created = created,
                bytes = encrypted.length(),
                sha256 = digest,
                payload = objectRef.id,
            ).also { it.validate() }.signed(key)
            manifest.writeText(remoteBackupJson.encodeToString(commit))
            val committed = store.upload("nyanime-$id.json", manifest)
            store.download(committed.id, checkFile, 16 * 1024)
            check(checkFile.readText() == manifest.readText())
            return RemoteBackupEntry(committed.id, commit)
        } finally {
            checkFile.delete()
            manifest.delete()
        }
    }

    suspend fun restore(entry: RemoteBackupEntry, output: File, key: ByteArray, validate: (File) -> Unit) {
        val encrypted = File.createTempFile("receive-", ".tmp", directory)
        try {
            require(entry.commit.verify(key))
            store.download(entry.commit.payload, encrypted, entry.commit.bytes)
            check(encrypted.length() == entry.commit.bytes && encrypted.remoteSha256() == entry.commit.sha256)
            interruptibleFileInput(encrypted, currentCoroutineContext()).use { input ->
                val header = SyncBackupCipher.readHeader(input)
                require(header.protection.space == entry.commit.device && header.protection.id == entry.commit.id)
                output.outputStream().use { SyncBackupCipher.decrypt(input, it, CRYPTO_DOMAIN, header, key) }
            }
            validate(output)
        } catch (failure: Exception) {
            output.delete()
            throw failure
        } finally {
            encrypted.delete()
        }
    }

    suspend fun retain(key: ByteArray, device: String, latestId: String, count: Int) {
        require(count in 3..30)
        val entries = list(key).filter { it.commit.device == device }
        // No destructive action if the new commit cannot be found and authenticated.
        check(entries.any { it.commit.id == latestId })
        entries.drop(count).filter { it.commit.id != latestId }.forEach { entry ->
            store.delete(entry.objectId)
            store.delete(entry.commit.payload)
        }
    }

    companion object {
        // Domain separation for the existing streaming cipher; unrelated to Telegram user IDs.
        private const val CRYPTO_DOMAIN = 794662013L
    }
}

private fun interruptibleFileInput(file: File, context: kotlin.coroutines.CoroutineContext): java.io.InputStream =
    object : java.io.FilterInputStream(file.inputStream()) {
        override fun read(): Int {
            context.ensureActive()
            return super.read()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            context.ensureActive()
            return `in`.read(buffer, offset, length)
        }
    }

package it.lorelore.nyanime.backup.remote

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import eu.kanade.tachiyomi.data.backup.BackupFileValidator
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

internal class RemoteBackupRepository(private val context: Context) {
    val vault = RemoteBackupVault(context)
    private val directory = File(context.noBackupFilesDir, "remote-backup-files-v1").apply { mkdirs() }

    suspend fun store(config: RemoteBackupConfiguration): RemoteFileStore = when (config.provider) {
        RemoteBackupProvider.WebDAV -> WebDavBackupStore(config.endpoint, config.username, config.password)
        RemoteBackupProvider.Drive -> {
            val authorization = DriveBackupAuthorization(context)
            val token = authorization.token(config.account)
            DriveBackupStore(token, onUnauthorized = { authorization.invalidate(token) })
        }
    }

    suspend fun check(config: RemoteBackupConfiguration) = withContext(Dispatchers.IO) {
        config.validate()
        store(config).apply {
            prepare()
            list()
        }
        Unit
    }

    suspend fun backups(config: RemoteBackupConfiguration): List<RemoteBackupEntry> = withContext(Dispatchers.IO) {
        val key = RemoteRecoveryKey.decode(config.recoveryKey)
        try {
            RemoteBackupEngine(store(config), directory).list(key)
        } finally {
            key.fill(0)
        }
    }

    suspend fun create(
        config: RemoteBackupConfiguration,
        workId: String,
        phase: suspend (String) -> Unit,
    ) = operation.withLock {
        withContext(Dispatchers.IO) {
            require(UUID.fromString(workId).toString() == workId)
            if (vault.get(config.provider)?.generation !=
                config.generation
            ) {
                throw RemoteStorageException("configuration")
            }
            val jobDirectory = File(directory, workId).apply { mkdirs() }
            val snapshot = File(jobDirectory, "snapshot.nyabk")
            val encrypted = File(jobDirectory, "payload.enc")
            val created = File(jobDirectory, "created")
            val key = RemoteRecoveryKey.decode(config.recoveryKey)
            var completed = false
            try {
                if (directory.usableSpace < 100L * 1024 * 1024) throw RemoteStorageException("space")
                if (!created.exists()) created.writeText(System.currentTimeMillis().toString())
                if (snapshot.exists()) {
                    // Only reuse a fully validated snapshot, never a partial file from a killed process.
                    try {
                        BackupFileValidator(context).validate(Uri.fromFile(snapshot))
                    } catch (
                        _: Exception,
                    ) {
                        snapshot.delete()
                        encrypted.delete()
                    }
                }
                if (!snapshot.exists() && !encrypted.exists()) {
                    phase("preparing")
                    BackupCreator(context, false).backup(Uri.fromFile(snapshot), BackupOptions.complete())
                }
                if (snapshot.exists()) BackupFileValidator(context).validate(Uri.fromFile(snapshot))
                val size = if (encrypted.exists()) encrypted.length() else snapshot.length()
                if (size !in 1..MAX_REMOTE_BYTES || directory.usableSpace < size * 2 + 16 * 1024 * 1024) {
                    throw RemoteStorageException("space")
                }
                val engine = RemoteBackupEngine(store(config), directory)
                engine.upload(
                    snapshot,
                    encrypted,
                    workId,
                    vault.device(),
                    android.os.Build.MODEL.take(100),
                    created.readText().toLong(),
                    key,
                    phase,
                )
                // Backups of another device or another key are never pruned.
                phase("retention")
                try {
                    engine.retain(key, vault.device(), workId, config.retention)
                } catch (
                    cancel: kotlinx.coroutines.CancellationException,
                ) {
                    throw cancel
                } catch (
                    _: Exception,
                ) { /* Verified new backup remains successful even if cleanup is unavailable. */ }
                val state = AtomicFile(File(directory, "success-${config.provider.name}-${config.generation}"))
                val output = state.startWrite()
                try {
                    output.write(System.currentTimeMillis().toString().toByteArray())
                    state.finishWrite(output)
                } catch (
                    failure: Exception,
                ) {
                    state.failWrite(output)
                    throw failure
                }
                completed = true
            } finally {
                key.fill(0)
                snapshot.delete()
                if (completed) clearRun(workId)
            }
        }
    }

    suspend fun prepareRestore(config: RemoteBackupConfiguration, entry: RemoteBackupEntry): File = operation.withLock {
        withContext(Dispatchers.IO) {
            val key = RemoteRecoveryKey.decode(config.recoveryKey)
            var output: File? = null
            try {
                if (context.cacheDir.usableSpace < entry.commit.bytes * 2 + 16 * 1024 * 1024) {
                    throw RemoteStorageException("space")
                }
                val target = File.createTempFile("remote-restore-", ".nyabk", context.cacheDir)
                output = target
                RemoteBackupEngine(store(config), directory).restore(entry, target, key) {
                    BackupFileValidator(context).validate(Uri.fromFile(it))
                }
                target
            } catch (failure: Exception) {
                output?.delete()
                throw failure
            } finally {
                key.fill(0)
            }
        }
    }

    fun lastSuccess(config: RemoteBackupConfiguration): Long = runCatching {
        File(directory, "success-${config.provider.name}-${config.generation}").readText().toLong()
    }.getOrDefault(0)

    fun clearRun(id: String) {
        require(UUID.fromString(id).toString() == id)
        val directory = File(directory, id)
        directory.listFiles()?.forEach { it.delete() }
        directory.delete()
    }

    companion object {
        private val operation = Mutex()
    }
}

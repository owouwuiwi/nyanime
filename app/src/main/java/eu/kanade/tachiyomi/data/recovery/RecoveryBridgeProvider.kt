package eu.kanade.tachiyomi.data.recovery

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.core.content.pm.PackageInfoCompat
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.restore.BackupRestorer
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Narrow IPC: no arbitrary file paths, SQL, extension execution or secret-bearing diagnostics. */
open class RecoveryBridgeProvider : ContentProvider() {
    protected open val imports: Boolean = false
    private data class Operation(val owner: String, val file: File, @Volatile var result: Bundle)
    private val operations = ConcurrentHashMap<String, Operation>()
    private val executor = Executors.newSingleThreadExecutor()
    override fun onCreate(): Boolean {
        bridgeDirectory().listFiles()?.filter {
            it.isFile && it.name.matches(Regex("(?:verify-)?[0-9a-f-]{36}\\.nyabk"))
        }?.forEach { it.delete() }
        return true
    }
    private fun bridgeDirectory() = File(
        requireNotNull(context).cacheDir,
        "recovery-bridge-${if (imports) "import" else "export"}",
    ).apply {
        mkdirs()
    }

    @Synchronized override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = requireNotNull(context)
        if (method == "describe") return describe()
        val caller = RecoveryAccess(ctx).caller()
        if (method == "access") return Bundle().apply { putBoolean("authorized", true) }
        require(method != "backup" || !imports)
        require(method !in setOf("prepare_restore", "restore") || imports)
        return when (method) {
            "backup", "prepare_restore" -> {
                check(operations.values.none { it.result.getString("phase") in ACTIVE })
                check(operations.size < 4) { "Release the previous recovery operation" }
                val id = UUID.randomUUID().toString()
                val directory = bridgeDirectory()
                val operation = Operation(
                    caller,
                    File(directory, "$id.nyabk"),
                    Bundle().apply {
                        putString("phase", if (method == "backup") "preparing" else "awaiting_file")
                    },
                )
                operations[id] = operation
                if (method == "backup") executor.execute { execute(operation, false) }
                Bundle().apply {
                    putString("id", id)
                    putString(
                        "uri",
                        "content://${ctx.packageName}.recovery${if (imports) ".restore" else ""}/backup/$id",
                    )
                }
            }
            "status" -> Bundle(requireOperation(arg, caller).result)
            "restore" -> {
                val operation = requireOperation(arg, caller)
                check(operation.result.getString("phase") == "awaiting_file")
                require(operation.file.length() in 1..MAX_BYTES)
                require(sha256(operation.file) == extras?.getString("sha256"))
                operation.result = Bundle().apply { putString("phase", "restoring") }
                executor.execute { execute(operation, true) }
                Bundle(operation.result)
            }
            "release" -> {
                val operation = requireOperation(arg, caller)
                check(operation.result.getString("phase") !in ACTIVE)
                operation.file.delete()
                operations.remove(arg)
                Bundle.EMPTY
            }
            else -> throw IllegalArgumentException("Unsupported recovery operation")
        }
    }

    private fun execute(operation: Operation, restore: Boolean) {
        try {
            val ctx = requireNotNull(context)
            RecoveryRuntime.initialize(ctx.applicationContext as Application, restore)
            runBlocking {
                if (restore) {
                    val decoded = BackupDecoder(ctx).decode(Uri.fromFile(operation.file))
                    val report = BackupRestorer(ctx, BackupNotifier(ctx), false, emergency = true, onProgress = {
                            completed,
                            total,
                        ->
                        operation.result =
                            Bundle().apply {
                                putString("phase", "restoring")
                                putInt("completed", completed)
                                putInt("total", total)
                            }
                    }).restore(Uri.fromFile(operation.file), RestoreOptions(extensions = true))
                    check(report.errorCount == 0) { "Incomplete restore" }
                    val verification = File(operation.file.parentFile, "verify-${operation.file.name}")
                    try {
                        BackupCreator(
                            ctx,
                            false,
                            emergency = true,
                        ).backup(Uri.fromFile(verification), BackupOptions.complete())
                        val after = BackupDecoder(ctx).decode(Uri.fromFile(verification))
                        RecoveryBackupIntegrity.verify(decoded, after)
                    } finally {
                        verification.delete()
                    }
                    RecoveryJournal.record(ctx, "restored")
                } else {
                    BackupCreator(
                        ctx,
                        false,
                        emergency = true,
                    ).backup(Uri.fromFile(operation.file), BackupOptions.complete()) { phase ->
                        operation.result = Bundle().apply { putString("phase", phase) }
                    }
                }
                require(operation.file.length() in 1..MAX_BYTES)
                val backup = BackupDecoder(ctx).decode(Uri.fromFile(operation.file))
                operation.result = Bundle().apply {
                    putString("phase", "verified")
                    putString("sha256", sha256(operation.file))
                    putLong("bytes", operation.file.length())
                    putInt("anime", backup.backupAnime.size)
                    putInt("manga", backup.backupManga.size)
                    putInt("episodes", backup.backupAnime.sumOf { it.episodes.size })
                    putInt("chapters", backup.backupManga.sumOf { it.chapters.size })
                    putInt("extensions", backup.backupExtensions.size)
                }
            }
        } catch (error: Throwable) {
            // Stack locations only: no URLs, credential values, backup contents or exception messages.
            android.util.Log.e(
                "RecoveryBridge",
                error.javaClass.simpleName + " at " + error.stackTrace.take(8).joinToString(),
            )
            operation.result =
                Bundle().apply {
                    putString("phase", "failed")
                    putString("error", error.javaClass.simpleName)
                }
        }
    }

    private fun requireOperation(id: String?, caller: String): Operation =
        requireNotNull(operations[requireNotNull(id)]).also { require(it.owner == caller) }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(uri.pathSegments.size == 2 && uri.pathSegments[0] == "backup")
        val operation = requireOperation(uri.lastPathSegment, RecoveryAccess(requireNotNull(context)).caller())
        return when (mode) {
            "r" -> {
                check(operation.result.getString("phase") == "verified")
                ParcelFileDescriptor.open(operation.file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            "w" -> {
                check(operation.result.getString("phase") == "awaiting_file")
                ParcelFileDescriptor.open(
                    operation.file,
                    ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or
                        ParcelFileDescriptor.MODE_WRITE_ONLY,
                )
            }
            else -> throw IllegalArgumentException("Unsupported recovery file mode")
        }
    }

    @Suppress("DEPRECATION")
    private fun describe(): Bundle {
        val ctx = requireNotNull(context)
        val flags = if (android.os.Build.VERSION.SDK_INT >=
            28
        ) {
            android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            android.content.pm.PackageManager.GET_SIGNATURES
        }
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, flags)
        val signatures = if (android.os.Build.VERSION.SDK_INT >=
            28
        ) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            info.signatures.orEmpty()
        }
        return Bundle().apply {
            putInt("protocol", 1)
            putInt("backupFormat", 1)
            putString("package", ctx.packageName)
            putString("versionName", BuildConfig.VERSION_NAME)
            putLong("versionCode", PackageInfoCompat.getLongVersionCode(info))
            putStringArrayList("signers", ArrayList(signatures.map { digest(it.toByteArray()) }))
            putString("repository", eu.kanade.tachiyomi.data.updater.GITHUB_REPO)
            putString("service", RecoveryBridgeService::class.java.name)
            putString("restoreService", RecoveryRestoreService::class.java.name)
            putString("health", RecoveryJournal.read(ctx).toString())
        }
    }

    override fun getType(uri: Uri) = "application/x-nyanime-backup"
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException()
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException()
    companion object {
        const val MAX_BYTES = 512L * 1024 * 1024
        private val ACTIVE = setOf("preparing", "saving", "verifying", "restoring")
        fun digest(
            bytes: ByteArray,
        ): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val bytes = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(bytes)
                    if (count <
                        0
                    ) {
                        break
                    }
                    digest.update(bytes, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

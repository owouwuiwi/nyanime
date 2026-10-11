package eu.kanade.tachiyomi.data.migration

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.preference.PreferenceManager
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.recovery.RecoveryRuntime
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.DigestOutputStream
import java.security.MessageDigest

internal data class PreparedMigration(val file: File, val digest: String, val manifest: MigrationManifest)

internal object MigrationSnapshot {
    fun roots(context: Context): Map<String, File> = buildMap {
        put("files", context.filesDir)
        put("databases", File(context.applicationInfo.dataDir, "databases"))
        put("preferences", File(context.applicationInfo.dataDir, "shared_prefs"))
        put("no_backup", context.noBackupFilesDir)
        put("webview", File(context.applicationInfo.dataDir, "app_webview"))
        context.getExternalFilesDir(null)?.let { put("external", it) }
    }

    fun storage(context: Context): String? = PreferenceManager.getDefaultSharedPreferences(context)
        .getString(
            tachiyomi.core.common.preference.Preference.appStateKey("storage_dir"),
            tachiyomi.core.common.storage.AndroidStorageFolderProvider(context).path(),
        )

    fun prepare(context: Context, cancelled: () -> Boolean = { false }, progress: (Long) -> Unit): PreparedMigration {
        val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        MigrationState.renew(context)
        val lease = timer.scheduleAtFixedRate({
            MigrationState.renew(context)
        }, 2, 2, java.util.concurrent.TimeUnit.SECONDS)
        try {
            return prepareLocked(context, cancelled, progress)
        } finally {
            lease.cancel(false)
            timer.shutdown()
            timer.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
            // Only snapshot creation requires exclusive access; transfer uses immutable encrypted bytes.
            MigrationState.unfreeze(context)
        }
    }

    private fun prepareLocked(context: Context, cancelled: () -> Boolean, progress: (Long) -> Unit): PreparedMigration {
        check(!cancelled())
        check(context.packageName == BuildConfig.LEGACY_APPLICATION_ID)
        MigrationState.renew(context)
        MigrationState.stopOwnOtherProcesses(context, preserveMigration = true)
        val working = File(MigrationState.directory(context), "export").apply { mkdirs() }
        val portable = File(working, "portable").apply { mkdirs() }
        RecoveryRuntime.initialize(context.applicationContext as Application, false)
        runBlocking {
            val file = File(portable, "complete.nyabk")
            BackupCreator(context, false, emergency = true).backup(Uri.fromFile(file), BackupOptions.complete())
            BackupDecoder(context).decode(Uri.fromFile(file))
        }
        check(!cancelled()) { "Migration cancelled" }
        val secrets = File(working, "secrets").apply { mkdirs() }
        MigrationSecrets.export(context, File(secrets, "credentials.json"))
        val file = File(working, "snapshot.encrypted")
        val digest = MessageDigest.getInstance("SHA-256")
        var heartbeat = 0L
        val owned = MigrationStoragePaths.owned(context, storage(context))
        val shared = if (owned ==
            null
        ) {
            storage(context)?.let { MigrationSharedStorage.inventory(context, it) }
        } else {
            null
        }
        File(portable, "shared.json").writeText(
            org.json.JSONObject().put("inventory", shared).put("privateStoragePath", owned).toString(),
        )
        var plainBytes = 0L
        val manifest = try {
            file.outputStream().use { output ->
                val encrypted = DigestOutputStream(MigrationCrypto.encrypt(output), digest)
                val counted = object : java.io.FilterOutputStream(encrypted) {
                    override fun write(value: Int) {
                        out.write(value)
                        plainBytes++
                    }
                    override fun write(
                        bytes: ByteArray,
                        offset: Int,
                        length: Int,
                    ) {
                        out.write(bytes, offset, length)
                        plainBytes +=
                            length
                    }
                }
                MigrationArchive.write(
                    roots(context) + mapOf("portable" to portable, "secrets" to secrets),
                    counted,
                    context.packageName,
                    BuildConfig.VERSION_CODE,
                    storage(context),
                    include = ::include,
                    progress = { bytes ->
                        check(!cancelled()) { "Migration cancelled" }
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - heartbeat > 1000) {
                            check(MigrationState.state(context).optLong("grantUntil") > System.currentTimeMillis())
                            heartbeat = now
                            MigrationState.renew(context)
                            progress(bytes)
                        }
                    },
                )
            }
        } finally {
            portable.deleteRecursively()
            secrets.deleteRecursively()
        }
        check(!cancelled()) { "Migration cancelled" }
        File(working, "ready.json").writeText(
            org.json.JSONObject().put("plainBytes", plainBytes)
                .put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
                .put(
                    "manifest",
                    kotlinx.serialization.json.Json.encodeToString(MigrationManifest.serializer(), manifest),
                ).toString(),
        )
        val saved = org.json.JSONObject(File(working, "ready.json").readText())
        return PreparedMigration(file, saved.getString("sha256"), manifest)
    }

    private fun include(path: String): Boolean {
        val name = path.substringAfterLast('/')
        if (name.endsWith("-shm") || name.endsWith(".lock")) return false
        if (path.startsWith("no_backup/identity-migration/")) return false
        if (path.split('/').any { it.startsWith(".migration-") }) return false
        if (path.startsWith("databases/androidx.work.workdb")) return false
        if (path.startsWith("preferences/androidx.work") ||
            path.startsWith("preferences/com.google.android.gms")
        ) {
            return false
        }
        if (path in setOf("files/recovery-access.json", "files/recovery-health.json")) return false
        if (path.startsWith("files/community") || path.startsWith("no_backup/community")) return false
        if (path.startsWith("webview/") &&
            path.split('/').any {
                it in setOf("Cache", "Code Cache", "GPUCache", "Crashpad", "blob_storage")
            }
        ) {
            return false
        }
        return true
    }
}

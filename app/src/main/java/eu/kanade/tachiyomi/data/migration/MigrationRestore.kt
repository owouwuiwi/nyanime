package eu.kanade.tachiyomi.data.migration

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.StatFs
import androidx.preference.PreferenceManager
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.recovery.RecoveryBackupIntegrity
import eu.kanade.tachiyomi.data.recovery.RecoveryRuntime
import eu.kanade.tachiyomi.data.rin.RinArchive
import eu.kanade.tachiyomi.data.rin.RinStorage
import kotlinx.coroutines.runBlocking
import java.io.File

internal object MigrationRestore {
    fun enoughSpace(context: Context, bytes: Long): Boolean = bytes >= 0 &&
        bytes < (Long.MAX_VALUE - 64 * 1024 * 1024) / 3 &&
        StatFs(context.noBackupFilesDir.path).availableBytes >= bytes * 3 + 64 * 1024 * 1024

    fun commit(context: Context, stage: File, manifest: MigrationManifest, storage: String?) {
        require(
            manifest.packageName == BuildConfig.LEGACY_APPLICATION_ID &&
                manifest.versionCode in 219..BuildConfig.VERSION_CODE,
        )
        require(context.packageName == BuildConfig.PRODUCTION_APPLICATION_ID)
        val backup = File(stage, "portable/complete.nyabk")
        // The migration process intentionally has no application graph before committing files.
        BackupDecoder(context, kotlinx.serialization.protobuf.ProtoBuf).decode(Uri.fromFile(backup))
        for (name in listOf("tachiyomi.db", "tachiyomi.animedb")) {
            val file = File(stage, "databases/$name")
            require(file.isFile)
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
                database.rawQuery("PRAGMA quick_check", null).use { cursor ->
                    require(cursor.moveToFirst() && cursor.getString(0) == "ok")
                }
            }
        }
        val shared = org.json.JSONObject(File(stage, "portable/shared.json").readText())
        if (shared.has("privateStoragePath")) {
            require(storage == MigrationStoragePaths.destination(context, shared.getString("privateStoragePath")))
        }
        if (shared.has("inventory")) {
            requireNotNull(storage)
            MigrationSharedStorage.verify(context, requireNotNull(manifest.storageUri), Uri.parse(storage))
            require(MigrationSharedStorage.inventory(context, storage) == shared.getString("inventory"))
        }
        val entries = manifest.files.filter { it.path.substringBefore('/') !in setOf("portable", "secrets") }
        fun mapped(path: String) = if (path in setOf(
                "preferences/${manifest.packageName}_preferences.xml",
                "preferences/${manifest.packageName}_preferences.xml.bak",
            )
        ) {
            path.replace("${manifest.packageName}_preferences.xml", "${context.packageName}_preferences.xml")
        } else {
            path
        }
        val paths = entries.map { mapped(it.path).split('/').take(2).joinToString("/") }.toMutableSet()
        paths += setOf(
            "no_backup/cloud-sync-keys",
            "no_backup/watch-room.pending",
            "preferences/anime_schedule_credentials.xml",
            "preferences/telegram.session.v1.xml",
            "preferences/${context.packageName}_preferences.xml",
        )
        try {
            // Preflight must finish before normal startup is suspended or any files are replaced.
            MigrationState.renew(context)
            MigrationState.stopOwnOtherProcesses(context)
            MigrationTransaction.begin(context, paths)
            val destinations = MigrationSnapshot.roots(context)
            manifest.files.filter { it.path.substringBefore('/') !in setOf("portable", "secrets") }.forEach { entry ->
                val root = entry.path.substringBefore('/')
                val relative = mapped(entry.path).substringAfter('/')
                val target = File(checkNotNull(destinations[root]), relative)
                check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
                val source = File(stage, entry.path)
                // Copy across storage volumes, then atomically publish the verified file.
                val temporary = File(target.parentFile, "${target.name}.migration-part")
                source.inputStream().use { input -> temporary.outputStream().use { input.copyTo(it, 256 * 1024) } }
                require(MigrationArchive.hash(temporary) == entry.sha256)
                check(temporary.renameTo(target))
                if (root == "files" &&
                    (relative.endsWith(".rin") || relative.endsWith(".ext"))
                ) {
                    check(target.setReadOnly())
                }
            }
            // Bound secrets are deliberately rewrapped; the old ciphertext alone is unusable.
            File(context.noBackupFilesDir, "cloud-sync-keys").deleteRecursively()
            RinStorage(context).records().forEach { record ->
                RinArchive.verify(RinStorage(context).file(record), record.signer, record.sha256)
            }
            val verification = context.contentResolver.call(
                Uri.parse("content://${context.packageName}.migration.verify"),
                "verify",
                null,
                null,
            )
            if (verification?.getBoolean("verified") !=
                true
            ) {
                throw MigrationFailure.Remote(verification?.getString("error") ?: "verify:Unavailable")
            }
            MigrationState.update(context) {
                it.put("complete", true)
                it.remove("frozenUntil")
                it.remove("grant")
                it.put("verifiedFiles", manifest.files.size)
                it.put("verifiedBytes", manifest.bytes)
            }
            // Verified commit is irreversible here; cleanup can be retried without rolling data back.
            runCatching { MigrationTransaction.finish(context) }
        } catch (error: Exception) {
            try {
                // The verifier must release copied database handles before the originals return.
                MigrationState.stopOwnOtherProcesses(context)
                MigrationTransaction.rollback(context)
            } catch (rollback: Exception) {
                rollback.addSuppressed(error)
                MigrationFailure.record(context, "rollback", rollback)
                throw rollback
            } finally {
                MigrationState.unfreeze(context)
            }
            throw error
        }
    }
}

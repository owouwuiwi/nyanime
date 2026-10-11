package eu.kanade.tachiyomi.data.migration

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceManager
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.recovery.RecoveryBackupIntegrity
import eu.kanade.tachiyomi.data.recovery.RecoveryRuntime
import kotlinx.coroutines.runBlocking
import java.io.File

/** Fresh process: never validates copied databases through a stale driver or preference cache. */
class MigrationVerifyProvider : ContentProvider() {
    override fun onCreate() = true

    @Synchronized override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == android.os.Process.myUid() && method == "verify")
        val context = requireNotNull(context)
        check(MigrationState.state(context).has("transaction"))
        try {
            val stage = File(MigrationState.directory(context), "stage")
            MigrationSecrets.restore(context, File(stage, "secrets/credentials.json"))
            val preferences = PreferenceManager.getDefaultSharedPreferences(context)
            val edit = preferences.edit().remove("automatically_trust_extensions")
            MigrationState.state(context).optString("storage").takeIf(String::isNotEmpty)?.let {
                edit.putString(tachiyomi.core.common.preference.Preference.appStateKey("storage_dir"), it)
            }
            check(edit.commit())
            RecoveryRuntime.initialize(context.applicationContext as Application, false)
            return runBlocking {
                val verification = File(MigrationState.directory(context), "verification.nyabk")
                try {
                    BackupCreator(
                        context,
                        false,
                        emergency = true,
                    ).backup(Uri.fromFile(verification), BackupOptions.complete())
                    val before = BackupDecoder(context).decode(Uri.fromFile(File(stage, "portable/complete.nyabk")))
                    val after = BackupDecoder(context).decode(Uri.fromFile(verification))
                    RecoveryBackupIntegrity.verify(before, after)
                    check(
                        before.newsState == after.newsState &&
                            before.guidedReading == after.guidedReading &&
                            before.readerSpreads == after.readerSpreads,
                    )
                    Bundle().apply { putBoolean("verified", true) }
                } finally {
                    verification.delete()
                }
            }
        } catch (error: Exception) {
            MigrationFailure.record(context, "verify", error)
            return Bundle().apply {
                putBoolean("verified", false)
                putString("error", MigrationFailure.code("verify", error))
            }
        } finally {
            Handler(Looper.getMainLooper()).postDelayed({
                android.os.Process.killProcess(android.os.Process.myPid())
            }, 1500)
        }
    }
    override fun getType(uri: Uri): String? = null
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
}

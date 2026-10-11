package eu.kanade.tachiyomi.data.migration

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/** Export-only process: a retry never reuses a cached database driver or decrypted preferences. */
class MigrationSnapshotProvider : ContentProvider() {
    override fun onCreate() = true

    @Synchronized override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == android.os.Process.myUid() && method == "prepare")
        val context = requireNotNull(context)
        fun cancelled() = MigrationState.state(context).let {
            arg == null || it.optString("grant") != arg || it.optLong("grantUntil") <= System.currentTimeMillis()
        }
        check(!cancelled())
        return try {
            MigrationSnapshot.prepare(context, ::cancelled) { count ->
                MigrationState.update(context) { it.put("preparedBytes", count) }
            }
            Bundle().apply { putBoolean("ready", true) }
        } catch (error: Exception) {
            MigrationFailure.record(context, "prepare", error)
            Bundle().apply { putString("error", MigrationFailure.code("prepare", error)) }
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

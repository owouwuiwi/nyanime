package eu.kanade.tachiyomi.data.theme

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import eu.kanade.domain.ui.ThemeController
import eu.kanade.domain.ui.ThemeSettingsRepository
import eu.kanade.domain.ui.model.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** One persisted theme, owned by the host process. No cross-process SharedPreferences writes. */
class AppearanceProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == Process.myUid()) { "Internal appearance access only" }
        // Binder calls wait for Application.onCreate to finish before touching the host's DI.
        return runBlocking(Dispatchers.Main.immediate) {
            when (method) {
                "read" -> Unit
                "select" -> Injekt.get<ThemeController>().select(ThemeMode.valueOf(requireNotNull(arg))).getOrThrow()
                else -> throw IllegalArgumentException("Unknown appearance operation")
            }
            Bundle().apply { putString("mode", Injekt.get<ThemeSettingsRepository>().current().mode.name) }
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
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException()
}

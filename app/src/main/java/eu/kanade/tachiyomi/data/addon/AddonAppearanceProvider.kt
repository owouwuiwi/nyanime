package eu.kanade.tachiyomi.data.addon

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import eu.kanade.domain.ui.ThemeController
import eu.kanade.domain.ui.ThemeSettingsRepository
import eu.kanade.domain.ui.model.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Narrow, revocable theme capability. No preferences, files or credentials are exposed. */
class AddonAppearanceProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = requireNotNull(context)
        val callers = ctx.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty().toSet()
        val registry = AddonRepository.get(ctx).registry
        runBlocking { registry.reload() }
        require(registry.state.value.any { it.packageName in callers && it.usable && it.sharesAppearance })
        return runBlocking(Dispatchers.Main.immediate) {
            when (method) {
                "read" -> Unit
                "select" -> Injekt.get<ThemeController>().select(ThemeMode.valueOf(requireNotNull(arg))).getOrThrow()
                else -> throw IllegalArgumentException("Unknown addon operation")
            }
            Bundle().apply {
                putString("mode", Injekt.get<ThemeSettingsRepository>().current().mode.name)
                putString("locale", ctx.resources.configuration.locales.toLanguageTags())
            }
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

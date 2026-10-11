package eu.kanade.tachiyomi.data.rin

import android.content.Context
import eu.kanade.tachiyomi.util.system.ChildFirstPathClassLoader
import nyanime.addon.api.RinEpisodeGuideProvider

/** Guide bundles have no activity, background service or source registration. */
internal object RinGuideRuntime {
    data class Loaded(val entry: RinEpisodeGuideProvider, val context: Context)
    private val loaded = mutableMapOf<String, Loaded>()

    @Synchronized
    fun load(context: Context, packageName: String): Loaded {
        val storage = RinStorage(context)
        val record = requireNotNull(storage.record(packageName))
        require(
            record.enabled &&
                eu.kanade.tachiyomi.extension.ExtensionPackageConsent.trusted(record) &&
                record.manifest.medium == "guide" &&
                RinRepository.compatible(record.manifest, record.format),
        )
        loaded[record.sha256]?.let { return it }
        val file = storage.file(record)
        require(eu.kanade.tachiyomi.extension.ExtensionPackageArchive.verify(context, file, record) == record.manifest)
        val loader = ChildFirstPathClassLoader(file.absolutePath, null, context.classLoader, addon = true)
        val provider = RinLoading.guard {
            Class.forName(
                record.manifest.entryPoints.single(),
                false,
                loader,
            ).getDeclaredConstructor().newInstance() as RinEpisodeGuideProvider
        }
        if (loaded.size >= 16) loaded.clear()
        return Loaded(provider, context.applicationContext).also { loaded[record.sha256] = it }
    }
}

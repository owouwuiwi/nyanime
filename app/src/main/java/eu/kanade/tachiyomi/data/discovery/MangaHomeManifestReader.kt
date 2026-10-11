package eu.kanade.tachiyomi.data.discovery

import android.content.Context
import eu.kanade.tachiyomi.extension.ExtensionPackageInspector
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import eu.kanade.tachiyomi.extension.manga.util.MangaExtensionLoader

fun interface MangaHomeManifestReader {
    fun read(extension: MangaExtension.Installed): List<ExtensionHomeManifest>
}

/** Reads shared and private APKs selected by the existing loader; never follows remote manifest URLs. */
class ApkMangaHomeManifestReader(private val context: Context) : MangaHomeManifestReader {
    override fun read(extension: MangaExtension.Installed): List<ExtensionHomeManifest> = runCatching {
        val info = MangaExtensionLoader.getMangaExtensionPackageInfoFromPkgName(context, extension.pkgName)
        val apk = eu.kanade.tachiyomi.data.rin.RinLoader.path(context, extension.pkgName)
            ?: info?.applicationInfo?.sourceDir ?: return emptyList()
        ExtensionPackageInspector.homes(apk)
    }.getOrDefault(emptyList())
}

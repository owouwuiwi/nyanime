package eu.kanade.tachiyomi.data.discovery

import android.content.Context
import eu.kanade.tachiyomi.extension.ExtensionPackageInspector
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.extension.anime.util.AnimeExtensionLoader

fun interface ExtensionHomeManifestReader {
    fun read(extension: AnimeExtension.Installed): List<ExtensionHomeManifest>
}

/** Reads shared and private APKs selected by the existing loader; never follows remote manifest URLs. */
class ApkExtensionHomeManifestReader(private val context: Context) : ExtensionHomeManifestReader {
    override fun read(extension: AnimeExtension.Installed): List<ExtensionHomeManifest> = runCatching {
        val info = AnimeExtensionLoader.getAnimeExtensionPackageInfoFromPkgName(context, extension.pkgName)
        val apk = eu.kanade.tachiyomi.data.rin.RinLoader.path(context, extension.pkgName)
            ?: info?.applicationInfo?.sourceDir ?: return emptyList()
        ExtensionPackageInspector.homes(apk)
    }.getOrDefault(emptyList())
}

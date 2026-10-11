package eu.kanade.tachiyomi.data.backup.create.creators

import android.content.Context
import android.content.pm.PackageManager
import eu.kanade.tachiyomi.data.backup.models.BackupExtension
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

class ExtensionsBackupCreator(
    private val context: Context,
    private val animeExtensionManager: AnimeExtensionManager? = Injekt.get(),
    private val mangaExtensionManager: MangaExtensionManager? = Injekt.get(),
) {

    operator fun invoke(): List<BackupExtension> {
        val installedExtensions = mutableListOf<BackupExtension>()
        val rin = eu.kanade.tachiyomi.data.rin.RinStorage(context)
        val packages = rin.records().map { it.manifest.packageName } +
            if (animeExtensionManager == null || mangaExtensionManager == null) {
                context.packageManager.getInstalledPackages(PackageManager.GET_CONFIGURATIONS)
                    .filter { info ->
                        info.reqFeatures.orEmpty().any { feature ->
                            feature.name in setOf("tachiyomi.animeextension", "tachiyomi.extension")
                        }
                    }.map { it.packageName }
            } else {
                animeExtensionManager.installedExtensionsFlow.value.map { it.pkgName } +
                    mangaExtensionManager.installedExtensionsFlow.value.map { it.pkgName }
            }
        packages.distinct().forEach { packageName ->
            val record = rin.record(packageName)
            if (record != null) {
                installedExtensions +=
                    BackupExtension(
                        packageName,
                        rin.file(record).readBytes(),
                        "rin",
                        record.signer,
                        record.repository,
                        record.enabled,
                    )
            } else {
                val privateFile = File(context.filesDir, "exts/$packageName.ext")
                val file = if (privateFile.isFile) {
                    privateFile
                } else {
                    File(
                        context.packageManager.getApplicationInfo(
                            packageName,
                            PackageManager.GET_META_DATA,
                        ).publicSourceDir,
                    )
                }
                installedExtensions += BackupExtension(packageName, file.readBytes())
            }
        }
        context.packageManager.getInstalledPackages(PackageManager.GET_CONFIGURATIONS).filter { info ->
            info.reqFeatures?.any { it.name == eu.kanade.tachiyomi.data.news.NewsExtensionRegistry.FEATURE } == true
        }.forEach { info ->
            info.applicationInfo?.sourceDir?.let { path ->
                installedExtensions.add(BackupExtension(info.packageName, File(path).readBytes()))
            }
        }
        return installedExtensions.distinctBy { it.pkgName }
    }
}

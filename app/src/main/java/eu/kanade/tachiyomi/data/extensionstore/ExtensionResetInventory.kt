package eu.kanade.tachiyomi.data.extensionstore

import android.content.Context
import android.content.pm.PackageManager
import android.util.AtomicFile
import eu.kanade.domain.extension.ExtensionCatalogueTrust
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.rin.RinRepository
import eu.kanade.tachiyomi.extension.ExtensionPackageDiscovery
import mihon.domain.extension.anime.repository.AnimeExtensionStoreRepository
import mihon.domain.extensionrepo.manga.repository.MangaExtensionRepoRepository
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

internal class ExtensionResetInventory(private val context: Context) {
    data class Extension(val packageName: String, val label: String, val androidPackage: Boolean)

    suspend fun clearCatalogues() {
        val anime = Injekt.get<AnimeExtensionStoreRepository>()
        anime.getAll().forEach { anime.remove(it.indexUrl) }
        val manga = Injekt.get<MangaExtensionRepoRepository>()
        manga.getAll().forEach { manga.deleteRepo(it.baseUrl) }
        Injekt.get<SourcePreferences>().apply {
            animeExtensionRepos().delete()
            mangaExtensionRepos().delete()
        }
        val preferences = Injekt.get<PreferenceStore>()
        ExtensionCatalogueTrust(preferences).clear()
        preferences.getString(Preference.appStateKey("extension_catalogue_authorities_v1"), "[]").delete()
        AtomicFile(File(context.noBackupFilesDir, "extension-store-replacement.json")).delete()
        listOf("prepared.apk", "prepared.part").forEach { name ->
            val file = File(context.filesDir, "extension-store/$name")
            check(!file.exists() || file.delete())
        }
    }

    @Suppress("DEPRECATION")
    fun read(): List<Extension> {
        val manager = context.packageManager
        // Query the package manager directly. A cached or unavailable inventory cannot confirm removal.
        val system = manager.getInstalledPackages(PackageManager.GET_CONFIGURATIONS).mapNotNull { info ->
            if (info.packageName == context.packageName ||
                info.reqFeatures.orEmpty().none { it.name in ExtensionPackageDiscovery.contracts }
            ) return@mapNotNull null
            val label = info.applicationInfo?.let(manager::getApplicationLabel)?.toString()
            Extension(info.packageName, label ?: info.packageName, androidPackage = true)
        }
        val records = RinRepository.get(context).storage.records().map { record ->
            Extension(record.manifest.packageName, record.manifest.name, androidPackage = false)
        }
        val legacy = legacyFiles().map { file ->
            Extension(file.nameWithoutExtension, file.nameWithoutExtension, androidPackage = false)
        }
        return (system + records + legacy).distinctBy { it.packageName to it.androidPackage }
            .sortedWith(compareByDescending<Extension> { it.androidPackage }.thenBy { it.label.lowercase() })
    }

    fun removePrivate() {
        val rin = RinRepository.get(context)
        rin.storage.records().forEach { rin.remove(it.manifest.packageName) }
        legacyFiles().forEach { check(it.delete() || !it.exists()) }
    }

    private fun legacyFiles(): List<File> {
        val directory = File(context.filesDir, "exts")
        if (!directory.exists()) return emptyList()
        return checkNotNull(directory.listFiles()).filter { it.isFile && it.extension == "ext" }
    }
}

package eu.kanade.tachiyomi.data.backup.create.creators

import android.content.Context
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
        val storage = eu.kanade.tachiyomi.data.rin.RinStorage(context)
        val records = storage.records().associateBy { it.manifest.packageName }
        val systems = context.packageManager.getInstalledPackages(
            eu.kanade.tachiyomi.extension.ExtensionPackageArchive.FLAGS,
        ).filter { info ->
            info.reqFeatures.orEmpty().any { feature ->
                feature.name in eu.kanade.tachiyomi.extension.ExtensionApkValidator.Kind.entries.map { it.feature }
            }
        }.associateBy { it.packageName }
        val active = animeExtensionManager?.installedExtensionsFlow?.value.orEmpty()
            .associate { it.pkgName to it.isShared } +
            mangaExtensionManager?.installedExtensionsFlow?.value.orEmpty().associate { it.pkgName to it.isShared }
        val retired = context.getSharedPreferences("extension_retired_imports_v1", Context.MODE_PRIVATE)
            .getStringSet("packages", emptySet()).orEmpty()
        val legacy = File(context.filesDir, "exts").listFiles().orEmpty()
            .filter { it.isFile && it.extension == "ext" && it.nameWithoutExtension !in retired }
            .associateBy { it.nameWithoutExtension }
        return (records.keys + systems.keys + active.keys + legacy.keys).map { name ->
            val record = records[name]
            val system = systems[name]
            val preferSystem = when {
                active[name] != null -> active[name] == true
                record?.isShared == true -> true
                record == null -> system != null && name !in legacy
                system == null -> false
                else -> {
                    val candidate = runCatching {
                        val source = File(requireNotNull(system.applicationInfo?.sourceDir))
                        val inspected = eu.kanade.tachiyomi.extension.ExtensionPackageArchive.inspect(context, source)
                        val metadata = eu.kanade.tachiyomi.extension.ExtensionPackageInspector.inspect(system) { 0 }
                        eu.kanade.tachiyomi.data.rin.RinRepository.compatible(inspected.manifest) &&
                            eu.kanade.tachiyomi.extension.ExtensionPackageChoice.samePublisher(
                                record.publisherSigners,
                                eu.kanade.tachiyomi.extension.ExtensionPackageChoice.Candidate(
                                    name,
                                    inspected.manifest.versionCode,
                                    metadata.signers,
                                    metadata.signerHistory,
                                ),
                            ) &&
                            inspected.manifest.versionCode > record.manifest.versionCode
                    }.getOrDefault(false)
                    candidate
                }
            }
            if (record != null && !preferSystem) {
                BackupExtension(
                    name, storage.file(record).readBytes(), record.format, record.signer,
                    record.repository, record.enabled, privatelyInstalled = true,
                    automaticUpdates = record.automaticUpdates, storagePublisher = record.storagePublisher,
                )
            } else {
                val private = !preferSystem && name in legacy
                val source = if (private) {
                    requireNotNull(legacy[name])
                } else {
                    File(requireNotNull(system?.applicationInfo?.sourceDir))
                }
                BackupExtension(
                    name,
                    source.readBytes(),
                    rinRepository = record?.repository.orEmpty(),
                    rinEnabled = record?.enabled ?: true,
                    privatelyInstalled = private,
                    automaticUpdates = record?.automaticUpdates ?: true,
                    storagePublisher = record?.storagePublisher.orEmpty(),
                )
            }
        }
    }
}

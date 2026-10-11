package eu.kanade.tachiyomi.extension

import android.content.Context
import eu.kanade.tachiyomi.data.rin.RinRecord
import eu.kanade.tachiyomi.data.rin.RinStorage
import uy.kohesive.injekt.api.get
import java.io.File

/** Installed component-free APKs use the same hosted TV/guide/addon runtime as imported packages. */
object ExtensionPackageInventory {
    fun hosted(context: Context, storage: RinStorage): List<RinRecord> {
        val private = storage.records()
        val system = ExtensionPackageDiscovery.candidates(
            context,
            ExtensionPackageArchive.FLAGS,
            setOf("nyanime.tvextension", "nyanime.guideextension", "nyanime.addon", "nyanime.novelextension"),
        ).mapNotNull { info ->
            if (info.reqFeatures.orEmpty().none {
                    it.name in
                        setOf(
                            "nyanime.tvextension",
                            "nyanime.guideextension",
                            "nyanime.addon",
                            "nyanime.novelextension",
                        )
                }
            ) {
                return@mapNotNull null
            }
            runCatching {
                val file = File(requireNotNull(info.applicationInfo?.sourceDir))
                val inspected = ExtensionPackageArchive.inspect(context, file)
                if (inspected.requiresAndroid) return@mapNotNull null
                val metadata = ExtensionPackageInspector.inspect(inspected.info) { 0 }
                val previous = private.firstOrNull { it.manifest.packageName == info.packageName }
                val record = storage.stage(
                    file,
                    metadata.signers.first(),
                    ExtensionPackageArchive.sha256(file),
                    previous?.repository.orEmpty(),
                )
                record.copy(
                    isShared = true,
                    catalogueConsent = previous?.catalogueConsent,
                    automaticUpdates = previous?.automaticUpdates ?: true,
                    trusted =
                    eu.kanade.domain.extension.ExtensionExecutionTrust(
                        uy.kohesive.injekt.Injekt.get(),
                    ).permits(info.packageName, metadata) ||
                        previous?.trusted == true &&
                        ExtensionPackageChoice.samePublisher(
                            previous.publisherSigners,
                            ExtensionPackageChoice.Candidate(
                                record.file,
                                record.manifest.versionCode,
                                record.publisherSigners,
                                record.publisherHistory,
                            ),
                        ),
                    enabled = previous?.enabled ?: true,
                )
            }.getOrNull()
        }
        return (private + system).groupBy { it.manifest.packageName }.values.mapNotNull { records ->
            val old = records.first()
            val chosen = ExtensionPackageChoice.select(
                records.map {
                    ExtensionPackageChoice.Candidate(
                        it.file + ":" + it.isShared,
                        it.manifest.versionCode,
                        it.publisherSigners,
                        it.publisherHistory,
                        eu.kanade.tachiyomi.data.rin.RinRepository.compatible(it.manifest, it.format),
                    )
                },
                old.file + ":" + old.isShared,
                old.publisherSigners,
            ) ?: return@mapNotNull null
            val selected = records.first { it.file + ":" + it.isShared == chosen.key }
            val current = storage.record(selected.manifest.packageName)
            if (selected.isShared &&
                selected.trusted &&
                selected != current &&
                (
                    current == null ||
                        current.manifest.knownSourceIds.isEmpty() ||
                        selected.manifest.preservesSourceIds(current.manifest.knownSourceIds)
                    )
            ) {
                if (eu.kanade.tachiyomi.data.rin.RinSessions.busy.value ||
                    eu.kanade.tachiyomi.data.addon.RinAddonRuntime.busy.value
                ) {
                    return@mapNotNull current ?: selected.copy(enabled = false)
                }
                try {
                    eu.kanade.tachiyomi.data.rin.RinLoader.instantiate(context, selected)
                    storage.activate(selected)
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    return@mapNotNull current ?: selected.copy(enabled = false)
                }
            }
            selected
        }
    }
}

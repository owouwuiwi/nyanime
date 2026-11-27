package it.lorelore.nyanime.novel.source

import android.content.Context
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.rin.RinLoader
import eu.kanade.tachiyomi.data.rin.RinRecord
import eu.kanade.tachiyomi.data.rin.RinRepository
import it.lorelore.nyanime.source.document.SourceDocumentBackedSource
import it.lorelore.nyanime.source.novel.NovelSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

internal data class NovelSourceRegistration(
    val source: NovelSource,
    val packageName: String,
    val revision: String,
    val providerId: String = "apk",
)

internal data class NovelSourceInventory(
    val sources: List<NovelSourceRegistration> = emptyList(),
    val unavailable: Set<String> = emptySet(),
    val initialized: Boolean = false,
)

/** Lazily observed package inventory. No provider code is loaded by the local document reader. */
internal class NovelPackageSources(context: Context, private val preferences: SourcePreferences) :
    NovelSourceProvider {
    override val id = "apk"
    private val app = context.applicationContext
    private val packages by lazy { RinRepository.get(app) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun eligible(record: RinRecord) =
        record.manifest.medium == "novel" &&
            record.enabled &&
            record.trusted &&
            RinRepository.compatible(record.manifest, record.format)

    override fun declarations() =
        packages.installed.mapLatest { records ->
            records.filter(::eligible).flatMap { record ->
                record.manifest.sources.map {
                    NovelSourceDeclaration(it.id, it.language, record.manifest.nsfw)
                }
            }
        }

    private val inventory by lazy {
        combine(
            packages.installed,
            preferences.enabledLanguages().changes(),
            preferences.showNsfwSource().changes(),
        ) { records, languages, adult ->
            Triple(records, languages, adult)
        }
            .mapLatest { (records, languages, adult) ->
                val unavailable = mutableSetOf<String>()
                val sources =
                    records
                        .filter { record ->
                            eligible(record) && (adult || !record.manifest.nsfw)
                        }
                        .flatMap { record ->
                            try {
                                RinLoader.instantiate(app, record)
                                    .filterIsInstance<NovelSource>()
                                    .filter { it.lang in languages || it.lang == "all" }
                                    .filterNot { source ->
                                        (source is SourceDocumentBackedSource).also { blocked ->
                                            if (blocked) unavailable += record.manifest.packageName
                                        }
                                    }
                                    .map {
                                        NovelSourceRegistration(
                                            it,
                                            record.manifest.packageName,
                                            record.sha256,
                                        )
                                    }
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                                unavailable += record.manifest.packageName
                                emptyList()
                            } catch (_: LinkageError) {
                                unavailable += record.manifest.packageName
                                emptyList()
                            }
                        }
                val duplicates = sources.groupBy { it.source.id }.values.filter { it.size > 1 }
                duplicates.flatten().forEach { unavailable += it.packageName }
                val collisions = duplicates.map { it.first().source.id }.toSet()
                NovelSourceInventory(
                    sources.filterNot { it.source.id in collisions },
                    unavailable,
                    true,
                )
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), NovelSourceInventory())
    }

    override fun observe() = inventory

    override suspend fun resolve(id: Long): NovelSourceRegistration {
        val registered =
            inventory.first { it.initialized }.sources.singleOrNull { it.source.id == id }
                ?: error("Novel source is unavailable")
        val current =
            packages.installed.value.singleOrNull {
                it.manifest.packageName == registered.packageName
            }
        check(
            current?.enabled == true &&
                current.trusted &&
                current.sha256 == registered.revision &&
                eu.kanade.tachiyomi.extension.ExtensionPackageConsent.trusted(current),
        ) {
            "Novel source changed or is no longer enabled"
        }
        check(preferences.showNsfwSource().get() || !registered.source.isNsfw)
        check(
            registered.source.lang in preferences.enabledLanguages().get() ||
                registered.source.lang == "all",
        )
        return registered
    }
}

package eu.kanade.tachiyomi.data.layer

import android.content.Context
import eu.kanade.domain.extension.ExtensionPackageMetadata
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.data.discovery.ExtensionHomeManifest
import eu.kanade.tachiyomi.data.news.NewsExtensionRegistry
import eu.kanade.tachiyomi.data.news.NewsLayerBinding
import eu.kanade.tachiyomi.data.rin.RinLoading
import eu.kanade.tachiyomi.data.rin.RinRecord
import eu.kanade.tachiyomi.data.rin.RinRepository
import eu.kanade.tachiyomi.data.tv.TvLayerBinding
import eu.kanade.tachiyomi.data.tv.TvServices
import eu.kanade.tachiyomi.extension.ExtensionPackageInspector
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.MangaSource
import eu.kanade.tachiyomi.util.system.ChildFirstPathClassLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nyanime.extension.api.layer.AnimeLayerFactoryV1
import nyanime.extension.api.layer.AnimeLayerV1
import nyanime.extension.api.layer.MangaLayerFactoryV1
import nyanime.extension.api.layer.MangaLayerV1
import nyanime.extension.api.layer.VersionedMangaLayerFactoryV1
import nyanime.extension.internal.AnimeLayerDispatch
import nyanime.extension.internal.MangaLayerDispatch
import nyanime.news.api.NewsSource
import nyanime.news.api.layer.NewsLayerFactoryV1
import nyanime.news.api.layer.NewsLayerV1
import nyanime.tv.api.TvSource
import nyanime.tv.api.layer.TvLayerFactoryV1
import nyanime.tv.api.layer.TvLayerV1
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

enum class MangaLayerStatus { ACTIVE, NOT_SELECTED, INCOMPATIBLE, DEPENDENCY_MISSING, FAILED }

data class MangaLayerChoice(
    val packageName: String,
    val name: String,
    val basePackage: String,
    val sourceId: Long,
    val capability: String,
    val status: MangaLayerStatus,
    val medium: String,
    val sourceName: String,
)

class MangaLayerRegistry(
    private val context: Context,
    extensions: MangaExtensionManager,
    animeExtensions: AnimeExtensionManager,
    private val news: NewsExtensionRegistry,
    private val tv: TvServices,
) {
    private val repository = RinRepository.get(context)
    private val preferences = context.getSharedPreferences("manga-layer-choices-v1", Context.MODE_PRIVATE)
    private val rebuild = MutableStateFlow(0L)
    private val _choices = MutableStateFlow(emptyList<MangaLayerChoice>())
    val choices = _choices.asStateFlow()
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()

    @Volatile
    private var homes: Map<Any, Home> = emptyMap()
    private val failures = ConcurrentHashMap.newKeySet<Failure>()
    private val attachments = mutableMapOf<AttachmentKey, Any>()
    private val loaders = mutableMapOf<String, ChildFirstPathClassLoader>()
    private val homeAssets = mutableMapOf<String, List<ExtensionHomeManifest>>()

    init {
        val catalogues = combine(extensions.installedExtensionsFlow, animeExtensions.installedExtensionsFlow) { manga, anime ->
            manga.flatMap { ext ->
                ext.sources.filterIsInstance<CatalogueSource>().map { source ->
                    Base("manga", ext.pkgName, source.id, ext.versionCode, ext.metadata, source.name, source.lang, source)
                }
            } + anime.flatMap { ext ->
                ext.sources.filterIsInstance<AnimeCatalogueSource>().map { source ->
                    Base("anime", ext.pkgName, source.id, ext.versionCode, ext.metadata, source.name, source.lang, source)
                }
            }
        }
        val bases = combine(catalogues, news.originalState, tv.registry.originals) { sources, newsSources, tvSources ->
            sources + newsSources.mapNotNull { ext ->
                ext.source?.let { source ->
                    Base("news", ext.packageName, 0, ext.versionCode, ext.metadata, source.name, source.language, source)
                }
            } + tvSources.values.map { ext ->
                val source = ext.source
                val metadata = ExtensionPackageMetadata(signers = ext.signers, signerHistory = ext.signerHistory)
                Base("tv", ext.packageName, source.id, ext.versionCode, metadata, source.name, source.language, source)
            }
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            combine(bases, repository.activeMangaLayers, rebuild) { installed, layers, _ ->
                installed to layers
            }.collect { (installed, layers) -> rebuild(installed, layers) }
        }
    }

    fun homes(source: Any): List<ExtensionHomeManifest> = homes[source]?.manifests.orEmpty()
    fun homeRevision(source: Any): String = homes[source]?.revision.orEmpty()

    fun select(choice: MangaLayerChoice) {
        if (choice !in choices.value || choice.status == MangaLayerStatus.INCOMPATIBLE) return
        if (choice.status == MangaLayerStatus.FAILED) {
            val record = repository.activeMangaLayers.value.firstOrNull { it.manifest.packageName == choice.packageName }
            failures.removeAll { it.attachment.hash == record?.sha256 && it.capability == choice.capability }
        }
        preferences.edit()
            .putString(key(choice.medium, choice.basePackage, choice.sourceId, choice.capability), choice.packageName)
            .apply()
        rebuild.update { it + 1 }
    }

    fun useOriginal(choice: MangaLayerChoice) {
        if (choice !in choices.value) return
        preferences.edit().putString(key(choice.medium, choice.basePackage, choice.sourceId, choice.capability), ORIGINAL).apply()
        rebuild.update { it + 1 }
    }

    private suspend fun rebuild(bases: List<Base>, layers: List<RinRecord>) {
        val bindings = IdentityHashMap<MangaSource, Map<String, MangaLayerDispatch.Binding>>()
        val animeBindings = IdentityHashMap<AnimeSource, Map<String, AnimeLayerDispatch.Binding>>()
        val newsBindings = IdentityHashMap<NewsSource, Map<String, NewsLayerBinding>>()
        val tvBindings = IdentityHashMap<TvSource, Map<String, TvLayerBinding>>()
        val nextHomes = IdentityHashMap<Any, Home>()
        val nextChoices = mutableListOf<MangaLayerChoice>()
        val records = layers.associateBy { it.manifest.packageName }
        val liveHashes = layers.map { it.sha256 }.toSet()
        val liveSources = bases.map { it.source }
        attachments.keys.removeAll { it.hash !in liveHashes || liveSources.none { source -> source === it.source } }
        failures.removeAll { it.attachment.hash !in liveHashes || liveSources.none { source -> source === it.attachment.source } }
        loaders.keys.retainAll(liveHashes)
        homeAssets.keys.retainAll(liveHashes)
        val targets = layers.flatMap { record ->
            record.manifest.mangaLayer?.targets.orEmpty().map { MangaLayerSelection.Offer(record.manifest.packageName, it) }
        }.groupBy { Triple(it.target.medium, it.target.packageName, it.target.sourceId) }

        for ((identity, offers) in targets) {
            val (medium, packageName, sourceId) = identity
            val base = bases.singleOrNull { it.medium == medium && it.packageName == packageName && it.sourceId == sourceId }
            val source = base?.source
            val compatible = if (base == null) emptyList() else offers.filter {
                it.target.matches(base.versionCode, base.metadata.signers, base.metadata.signerHistory)
            }
            val preferred = MangaLayerTarget.capabilitiesFor(medium).mapNotNull { cap ->
                preferences.getString(key(medium, packageName, sourceId, cap), null)?.let { cap to it }
            }.toMap()
            val available = if (source == null) emptyList() else compatible.mapNotNull { offer ->
                val record = records.getValue(offer.packageName)
                val caps = offer.target.capabilities.filterNot { cap ->
                    Failure(AttachmentKey(record.sha256, requireNotNull(base).versionCode, source), cap) in failures
                }.toSet()
                offer.copy(target = offer.target.copy(capabilities = caps)).takeIf { caps.isNotEmpty() }
            }
            val selected = MangaLayerSelection.resolve(available, preferred).toMutableMap()
            if (source != null && base != null) {
                var declaredHomes = emptyList<ExtensionHomeManifest>()
                for ((capability, offer) in selected.toMap()) {
                    val record = records.getValue(offer.packageName)
                    val availableForSource = try {
                        if (capability == "home") {
                            val parsed = homeAssets.getOrPut(record.sha256) {
                                val file = requireNotNull(repository.verifiedMangaLayerFile(record))
                                ExtensionPackageInspector.homes(file.absolutePath)
                            }.filter { it.source.name == base.name && it.source.lang == base.language }
                            declaredHomes = parsed
                            parsed.isNotEmpty()
                        } else {
                            val layer = attach(record, base)
                            layer != null
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                    if (!availableForSource) {
                        failures += Failure(AttachmentKey(record.sha256, base.versionCode, source), capability)
                        selected.remove(capability)
                    }
                }
                var changed: Boolean
                do {
                    changed = selected.entries.removeAll { (cap, offer) ->
                        !selected.keys.containsAll(offer.target.requires[cap].orEmpty())
                    }
                } while (changed)
                if ("home" in selected) {
                    val revision = selected.toSortedMap().entries.joinToString(":") {
                        records.getValue(it.value.packageName).sha256
                    }
                    nextHomes[source] = Home(declaredHomes, revision)
                }
                val code = selected.filterKeys { it != "home" }.mapValues { (cap, offer) ->
                    val record = records.getValue(offer.packageName)
                    val attachment = AttachmentKey(record.sha256, base.versionCode, source)
                    val failed: () -> Unit = {
                        failures += Failure(attachment, cap)
                        rebuild.update { it + 1 }
                    }
                    AttachmentBinding(attachments.getValue(attachment), record.sha256, failed)
                }
                when (source) {
                    is CatalogueSource -> bindings[source] = code.mapValues {
                        MangaLayerDispatch.Binding(it.value.layer as MangaLayerV1, it.value.failed)
                    }
                    is AnimeCatalogueSource -> animeBindings[source] = code.mapValues {
                        AnimeLayerDispatch.Binding(it.value.layer as AnimeLayerV1, it.value.failed)
                    }
                    is NewsSource -> newsBindings[source] = code.mapValues {
                        NewsLayerBinding(it.value.layer as NewsLayerV1, it.value.failed)
                    }
                    is TvSource -> tvBindings[source] = code.mapValues {
                        TvLayerBinding(it.value.layer as TvLayerV1, it.value.revision, it.value.failed)
                    }
                }
                val defaults = selected.filterKeys { it !in preferred }
                if (defaults.isNotEmpty()) {
                    val edit = preferences.edit()
                    defaults.forEach { (cap, offer) -> edit.putString(key(medium, packageName, sourceId, cap), offer.packageName) }
                    edit.apply()
                }
            }
            for (offer in offers) {
                val record = records.getValue(offer.packageName)
                for (capability in offer.target.capabilities.sorted()) {
                    val status = when {
                        offer !in compatible -> MangaLayerStatus.INCOMPATIBLE
                        base != null && Failure(AttachmentKey(record.sha256, base.versionCode, base.source), capability) in failures -> MangaLayerStatus.FAILED
                        selected[capability]?.packageName == offer.packageName -> MangaLayerStatus.ACTIVE
                        preferred[capability] == offer.packageName ||
                            available.count { capability in it.target.capabilities } == 1 && capability !in preferred ->
                            MangaLayerStatus.DEPENDENCY_MISSING
                        else -> MangaLayerStatus.NOT_SELECTED
                    }
                    nextChoices += MangaLayerChoice(
                        offer.packageName, record.manifest.name, packageName, sourceId, capability, status,
                        medium, base?.name ?: packageName,
                    )
                }
            }
        }
        MangaLayerDispatch.publish(bindings)
        AnimeLayerDispatch.publish(animeBindings)
        news.publishLayers(newsBindings)
        tv.registry.publishLayers(tvBindings)
        homes = nextHomes
        _choices.value = nextChoices.sortedWith(compareBy({ it.name }, { it.sourceId }, { it.capability }))
        _revision.update { it + 1 }
    }

    private fun attach(record: RinRecord, base: Base): Any? {
        val source = base.source
        val entryPoint = record.manifest.entryPoints.singleOrNull() ?: return null
        return attachments.getOrPut(AttachmentKey(record.sha256, base.versionCode, source)) {
            val loader = loaders.getOrPut(record.sha256) {
                val file = requireNotNull(repository.verifiedMangaLayerFile(record))
                ChildFirstPathClassLoader(file.absolutePath, null, context.classLoader)
            }
            RinLoading.guard {
                val type = Class.forName(entryPoint, false, loader)
                when (source) {
                    is CatalogueSource -> {
                        val factory = type.asSubclass(MangaLayerFactoryV1::class.java).getDeclaredConstructor().newInstance()
                        if (factory is VersionedMangaLayerFactoryV1) factory.attach(source, base.versionCode)
                        else factory.attach(source)
                    }
                    is AnimeCatalogueSource -> type.asSubclass(AnimeLayerFactoryV1::class.java)
                        .getDeclaredConstructor().newInstance().attach(source)
                    is NewsSource -> news.attachLayer(
                        type.asSubclass(NewsLayerFactoryV1::class.java).getDeclaredConstructor().newInstance(),
                        source,
                    )
                    is TvSource -> tv.attachLayer(
                        type.asSubclass(TvLayerFactoryV1::class.java).getDeclaredConstructor().newInstance(),
                        source,
                        record.manifest.packageName,
                        record.sha256,
                    )
                    else -> error("Unsupported layer source")
                }
            }
        }
    }

    private data class Base(
        val medium: String,
        val packageName: String,
        val sourceId: Long,
        val versionCode: Long,
        val metadata: ExtensionPackageMetadata,
        val name: String,
        val language: String,
        val source: Any,
    )
    private data class Home(val manifests: List<ExtensionHomeManifest>, val revision: String)
    private data class AttachmentBinding(val layer: Any, val revision: String, val failed: () -> Unit)
    private data class AttachmentKey(val hash: String, val baseVersion: Long, val source: Any)
    private data class Failure(val attachment: AttachmentKey, val capability: String)

    private companion object {
        const val ORIGINAL = ""
        fun key(medium: String, packageName: String, sourceId: Long, capability: String) =
            "$medium:$packageName:$sourceId:$capability"
    }
}

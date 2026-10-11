package eu.kanade.tachiyomi.source.manga

import android.content.Context
import eu.kanade.tachiyomi.data.download.manga.MangaDownloadManager
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.MangaSource
import eu.kanade.tachiyomi.source.online.HttpSource
import it.lorelore.nyanime.sources.LoadedSourceRevision
import it.lorelore.nyanime.sources.LoadedSourceRevisions
import it.lorelore.nyanime.studio.SourceStudioRepository
import it.lorelore.nyanime.studio.StudioDocuments
import it.lorelore.nyanime.studio.StudioMangaSource
import it.lorelore.nyanime.studio.StudioMedium
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import tachiyomi.domain.source.manga.model.StubMangaSource
import tachiyomi.domain.source.manga.repository.MangaStubSourceRepository
import tachiyomi.domain.source.manga.service.MangaSourceManager
import tachiyomi.source.local.entries.manga.LocalMangaSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.ConcurrentHashMap

class AndroidMangaSourceManager(
    private val context: Context,
    private val extensionManager: MangaExtensionManager,
    private val sourceRepository: MangaStubSourceRepository,
) : MangaSourceManager {

    private val _isInitialized = MutableStateFlow(false)
    override val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val downloadManager: MangaDownloadManager by injectLazy()

    private val scope = CoroutineScope(Job() + Dispatchers.IO)

    // Extensions may implement equality by source ID. Publication must still replace the actual code object.
    private class SourceSnapshot(val entries: ConcurrentHashMap<Long, MangaSource>)
    private val sourcesMapFlow = MutableStateFlow(SourceSnapshot(ConcurrentHashMap()))

    private val stubSourcesMap = ConcurrentHashMap<Long, StubMangaSource>()

    override val catalogueSources: Flow<List<CatalogueSource>> = sourcesMapFlow.map {
        it.entries.values.filterIsInstance<CatalogueSource>()
    }

    init {
        scope.launch {
            combine(extensionManager.installedExtensionsFlow, SourceStudioRepository.get(context).active) {
                    extensions,
                    definitions,
                ->
                extensions to
                    definitions
            }
                .collectLatest { (extensions, definitions) ->
                    val mutableMap = ConcurrentHashMap<Long, MangaSource>(
                        mapOf(
                            LocalMangaSource.ID to LocalMangaSource(
                                context,
                                Injekt.get(),
                                Injekt.get(),
                            ),
                        ),
                    )
                    val revisions = mutableMapOf<Long, String>()
                    extensions.forEach { extension ->
                        extension.sources.forEach {
                            mutableMap[it.id] = it
                            revisions[it.id] = extension.contentRevision ?: java.util.UUID.randomUUID().toString()
                            registerStubSource(StubMangaSource.from(it))
                        }
                    }
                    definitions.filter { it.medium == StudioMedium.MANGA }.forEach { definition ->
                        if (!mutableMap.containsKey(definition.sourceId)) {
                            val source = StudioMangaSource(definition, SourceStudioRepository.get(context))
                            mutableMap[source.id] = source
                            revisions[source.id] = StudioDocuments.fingerprint(definition)
                            registerStubSource(StubMangaSource.from(source))
                        }
                    }
                    LoadedSourceRevisions.registry.publish(
                        "manga",
                        mutableMap.map { (id, source) ->
                            LoadedSourceRevision(id, source, revisions[id] ?: "local-v1")
                        },
                    ) { sourcesMapFlow.value = SourceSnapshot(mutableMap) }
                    _isInitialized.value = true
                }
        }

        scope.launch {
            sourceRepository.subscribeAllManga()
                .collectLatest { sources ->
                    val mutableMap = stubSourcesMap.toMutableMap()
                    sources.forEach {
                        mutableMap[it.id] = it
                    }
                }
        }
    }

    override fun get(sourceKey: Long): MangaSource? {
        return sourcesMapFlow.value.entries[sourceKey]
    }

    override fun getOrStub(sourceKey: Long): MangaSource {
        return sourcesMapFlow.value.entries[sourceKey] ?: stubSourcesMap.getOrPut(sourceKey) {
            runBlocking { createStubSource(sourceKey) }
        }
    }

    override fun getOnlineSources() = sourcesMapFlow.value.entries.values.filterIsInstance<HttpSource>()

    override fun getCatalogueSources() = sourcesMapFlow.value.entries.values.filterIsInstance<CatalogueSource>()

    override fun getStubSources(): List<StubMangaSource> {
        val onlineSourceIds = getOnlineSources().map { it.id }
        return stubSourcesMap.values.filterNot { it.id in onlineSourceIds }
    }

    private fun registerStubSource(source: StubMangaSource) {
        scope.launch {
            val dbSource = sourceRepository.getStubMangaSource(source.id)
            if (dbSource == source) return@launch
            sourceRepository.upsertStubMangaSource(source.id, source.lang, source.name)
            if (dbSource != null) {
                downloadManager.renameSource(dbSource, source)
            }
        }
    }

    private suspend fun createStubSource(id: Long): StubMangaSource {
        sourceRepository.getStubMangaSource(id)?.let {
            return it
        }
        extensionManager.getSourceData(id)?.let {
            registerStubSource(it)
            return it
        }
        return StubMangaSource(id = id, lang = "", name = "")
    }
}

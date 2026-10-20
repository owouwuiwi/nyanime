package eu.kanade.tachiyomi.source.anime

import android.content.Context
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.data.download.anime.AnimeDownloadManager
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import it.lorelore.nyanime.sources.LoadedSourceRevision
import it.lorelore.nyanime.sources.LoadedSourceRevisions
import it.lorelore.nyanime.studio.SourceStudioRepository
import it.lorelore.nyanime.studio.StudioAnimeSource
import it.lorelore.nyanime.studio.StudioDocuments
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
import tachiyomi.domain.source.anime.model.StubAnimeSource
import tachiyomi.domain.source.anime.repository.AnimeStubSourceRepository
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.source.local.entries.anime.LocalAnimeSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.ConcurrentHashMap

class AndroidAnimeSourceManager(
    private val context: Context,
    private val extensionManager: AnimeExtensionManager,
    private val sourceRepository: AnimeStubSourceRepository,
) : AnimeSourceManager {

    private val _isInitialized = MutableStateFlow(false)
    override val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val downloadManager: AnimeDownloadManager by injectLazy()

    private val scope = CoroutineScope(Job() + Dispatchers.IO)

    // Extensions may implement equality by source ID. Publication must still replace the actual code object.
    private class SourceSnapshot(val entries: ConcurrentHashMap<Long, AnimeSource>)
    private val sourcesMapFlow = MutableStateFlow(SourceSnapshot(ConcurrentHashMap()))

    private val stubSourcesMap = ConcurrentHashMap<Long, StubAnimeSource>()

    override val sources: Flow<List<AnimeSource>> = sourcesMapFlow.map { it.entries.values.toList() }

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
                    val mutableMap = ConcurrentHashMap<Long, AnimeSource>(
                        mapOf(
                            LocalAnimeSource.ID to LocalAnimeSource(
                                context,
                                Injekt.get(),
                                Injekt.get(),
                                Injekt.get(),
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
                            registerStubSource(StubAnimeSource.from(it))
                        }
                    }
                    definitions.filter { it.medium == StudioMedium.VIDEO }.forEach { definition ->
                        if (!mutableMap.containsKey(definition.sourceId)) {
                            val source = StudioAnimeSource(definition, SourceStudioRepository.get(context))
                            mutableMap[source.id] = source
                            revisions[source.id] = StudioDocuments.fingerprint(definition)
                            registerStubSource(StubAnimeSource.from(source))
                        }
                    }
                    LoadedSourceRevisions.registry.publish(
                        "anime",
                        mutableMap.map { (id, source) ->
                            LoadedSourceRevision(id, source, revisions[id] ?: "local-v1")
                        },
                    ) { sourcesMapFlow.value = SourceSnapshot(mutableMap) }
                    _isInitialized.value = true
                }
        }

        scope.launch {
            sourceRepository.subscribeAllAnime()
                .collectLatest { sources ->
                    val mutableMap = stubSourcesMap.toMutableMap()
                    sources.forEach {
                        mutableMap[it.id] = it
                    }
                }
        }
    }

    override fun get(sourceKey: Long): AnimeSource? {
        return sourcesMapFlow.value.entries[sourceKey]
    }

    override fun getOrStub(sourceKey: Long): AnimeSource {
        return sourcesMapFlow.value.entries[sourceKey] ?: stubSourcesMap.getOrPut(sourceKey) {
            runBlocking { createStubSource(sourceKey) }
        }
    }

    override fun getAll(): List<AnimeSource> = sourcesMapFlow.value.entries.values.toList()

    override fun getOnlineSources(): List<AnimeHttpSource> {
        return sourcesMapFlow.value.entries.values.filterIsInstance<AnimeHttpSource>()
    }

    override fun getStubSources(): List<StubAnimeSource> {
        val onlineSourceIds = getAll().map { it.id }
        return stubSourcesMap.values.filterNot { it.id in onlineSourceIds }
    }

    private fun registerStubSource(source: StubAnimeSource) {
        scope.launch {
            val dbSource = sourceRepository.getStubAnimeSource(source.id)
            if (dbSource == source) return@launch
            sourceRepository.upsertStubAnimeSource(source.id, source.lang, source.name)
            if (dbSource != null) {
                downloadManager.renameSource(dbSource, source)
            }
        }
    }

    private suspend fun createStubSource(id: Long): StubAnimeSource {
        sourceRepository.getStubAnimeSource(id)?.let {
            return it
        }
        extensionManager.getSourceData(id)?.let {
            registerStubSource(it)
            return it
        }
        return StubAnimeSource(id = id, lang = "", name = "")
    }
}

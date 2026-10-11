package it.lorelore.nyanime.studio

import android.content.Context
import it.lorelore.nyanime.novel.source.NovelChapterPreparation
import it.lorelore.nyanime.novel.source.NovelSourceDeclaration
import it.lorelore.nyanime.novel.source.NovelSourceInventory
import it.lorelore.nyanime.novel.source.NovelSourceProvider
import it.lorelore.nyanime.novel.source.NovelSourceRegistration
import it.lorelore.nyanime.source.novel.NovelChapter
import it.lorelore.nyanime.source.novel.NovelContent
import it.lorelore.nyanime.source.novel.NovelDetails
import it.lorelore.nyanime.source.novel.NovelEntry
import it.lorelore.nyanime.source.novel.NovelHomeRequest
import it.lorelore.nyanime.source.novel.NovelHomeSection
import it.lorelore.nyanime.source.novel.NovelHomeSource
import it.lorelore.nyanime.source.novel.NovelResourceReference
import it.lorelore.nyanime.source.novel.NovelResourceRequest
import it.lorelore.nyanime.source.novel.NovelSearchPage
import it.lorelore.nyanime.source.novel.NovelSearchRequest
import it.lorelore.nyanime.source.novel.NovelSource
import it.lorelore.nyanime.source.novel.NovelStatus
import it.lorelore.nyanime.source.novel.NovelTextFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.URI

/**
 * Registry loading is local only. Graphs run for requested search, visible Home or content
 * operations.
 */
internal class StudioNovelSourceProvider(context: Context) : NovelSourceProvider {
    override val id = "studio"
    private val app = context.applicationContext
    private val repository = SourceStudioRepository.get(app)
    private val index by lazy { StudioNovelIndex.get(app, repository) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val records = flow {
        repository.awaitReady()
        emitAll(repository.records)
    }
    private val snapshots =
        records
            .map { records ->
                val unavailable = mutableSetOf<String>()
                val sources =
                    records
                        .filter { it.enabled && it.active?.medium == StudioMedium.NOVEL }
                        .mapNotNull { record ->
                            val source = requireNotNull(record.active)
                            try {
                                approved(record)
                                registration(source)
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                                unavailable += packageName(source)
                                null
                            }
                        }
                NovelSourceInventory(sources, unavailable, initialized = true)
            }
            .catch { failure ->
                if (failure is CancellationException) throw failure
                emit(NovelSourceInventory(unavailable = setOf("studio"), initialized = true))
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), NovelSourceInventory())

    override fun observe() = snapshots

    override suspend fun resolve(id: Long): NovelSourceRegistration {
        repository.awaitReady()
        val record =
            repository.records.value.singleOrNull { it.enabled && it.active?.sourceId == id }
                ?: error("Studio source is unavailable")
        return registration(approved(record))
    }

    override fun declarations() = records.map { values ->
        values.mapNotNull { record ->
            try {
                val source = approved(record)
                NovelSourceDeclaration(source.sourceId, source.language, source.adult)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                null
            }
        }
    }

    /** One shared local policy for declaration visibility, loading and explicit resolution. */
    private suspend fun approved(record: StudioRecord): StudioSource {
        check(record.enabled)
        val source = requireNotNull(record.active)
        require(
            source.medium == StudioMedium.NOVEL &&
                (!StudioBlueprints.hasScripts(source) || record.trusts(source)),
        )
        require(StudioValidation.source(source).isEmpty())
        requireNotNull(
            SourceActivationRequirements.proof(
                app,
                SourceActivationRequirements.declared(source),
                live = false,
            ),
        ) {
            "Source access must be configured"
        }
        return source
    }

    private fun registration(source: StudioSource) =
        NovelSourceRegistration(
            StudioNovelSource(source, repository, index),
            packageName(source),
            StudioDocuments.fingerprint(source),
            providerId = id,
        )

    private fun packageName(source: StudioSource) = "studio.${source.uuid}"
}

private fun JsonObject.text(key: String) = this[key]?.studioText()?.takeIf(String::isNotBlank)

private fun JsonObject.required(key: String) =
    requireNotNull(text(key)) { "Missing result field: $key" }

/** The same declarative graph serves testing, browsing, the reader and downloads. */
internal class StudioNovelSource(
    val definition: StudioSource,
    private val repository: SourceStudioRepository,
    private val index: StudioNovelIndex,
    private val draftPreview: Boolean = false,
    private val approvedDraftScripts: Boolean = false,
) : NovelSource, NovelChapterPreparation, NovelHomeSource {
    override val id = definition.sourceId
    override val name = definition.name
    override val lang = definition.language
    override val isNsfw = definition.adult

    override fun observePreparation(reference: String) =
        index.observe(StudioIndexRequest(definition, reference, draftPreview, approvedDraftScripts))

    override suspend fun pausePreparation(reference: String) =
        index.pause(StudioIndexRequest(definition, reference, draftPreview, approvedDraftScripts))

    override val homeSections: List<NovelHomeSection>
        get() = definition.home?.sections.orEmpty().map { NovelHomeSection(it.id, it.title) }

    override suspend fun home(request: NovelHomeRequest): NovelSearchPage {
        val section =
            requireNotNull(definition.home?.sections?.singleOrNull { it.id == request.sectionId })
        val graph = requireNotNull(definition.homeFlows[section.id])
        val result =
            repository
                .execute(
                    definition,
                    StudioFlow.POPULAR,
                    buildJsonObject {
                        put("section", section.id)
                        put("url", definition.baseUrl)
                        put("cursor", request.cursor.orEmpty())
                        put("page", 1)
                        definition.filters.forEach { field ->
                            val selected = section.filters[field.title] ?: field.initial
                            put("filter.${field.id}", StudioAssist.filterValue(field, selected))
                        }
                    },
                    overrideGraph = graph,
                    allowDraftScripts = approvedDraftScripts,
                    allowDraftCapabilities = draftPreview,
                )
                .value
        return page(result)
    }

    override suspend fun search(request: NovelSearchRequest): NovelSearchPage {
        val result =
            repository
                .execute(
                    definition,
                    StudioFlow.SEARCH,
                    buildJsonObject {
                        put("query", request.query)
                        put("cursor", request.cursor.orEmpty())
                        put("url", definition.baseUrl)
                        definition.filters.forEach { field ->
                            put("filter.${field.id}", request.filters[field.id] ?: field.initial)
                        }
                    },
                    allowDraftScripts = approvedDraftScripts,
                    allowDraftCapabilities = draftPreview,
                )
                .value
        return page(result)
    }

    private fun page(result: JsonElement): NovelSearchPage {
        val envelope = result as? JsonObject
        val raw = envelope?.get("items") ?: result
        val entries =
            requireNotNull(raw as? JsonArray) { "Return a list of titles" }
                .map { entry(it.jsonObject) }
        val cursor = envelope?.get("cursor")?.studioText()?.takeIf(String::isNotBlank)
        require(envelope?.get("hasNext")?.jsonPrimitive?.booleanOrNull != true || cursor != null) {
            "Declare an opaque cursor for the next search page"
        }
        return NovelSearchPage(entries, cursor)
    }

    override suspend fun details(reference: String): NovelDetails {
        val raw = run(StudioFlow.DETAILS, reference)
        val item = (if (raw is JsonArray) raw.single() else raw).jsonObject
        val normalized = JsonObject(item + ("url" to JsonPrimitive(reference)))
        return NovelDetails(
            entry(normalized),
            item.text("description").orEmpty(),
            words(item["genres"]),
            runCatching { NovelStatus.valueOf(item.text("status").orEmpty().uppercase()) }
                .getOrDefault(NovelStatus.UNKNOWN),
            lang,
        )
    }

    override suspend fun chapters(reference: String): List<NovelChapter> =
        preparedChapters(reference)

    override suspend fun preparedChapters(reference: String, force: Boolean): List<NovelChapter> =
        index.chapters(definition, reference, draftPreview, approvedDraftScripts, force).map { item ->
            NovelChapter(
                reference(item.required("url")),
                item.required("title"),
                item["number"]?.jsonPrimitive?.doubleOrNull,
                item.text("group"),
                item["uploaded"]?.jsonPrimitive?.longOrNull,
            )
        }

    override suspend fun content(chapterReference: String): NovelContent {
        val raw = run(StudioFlow.CONTENT, chapterReference)
        val item = (if (raw is JsonArray) raw.single() else raw).jsonObject
        return NovelContent(
            chapterReference,
            item.text("title").orEmpty(),
            item.required("body"),
            runCatching { NovelTextFormat.valueOf(item.text("format").orEmpty().uppercase()) }
                .getOrDefault(NovelTextFormat.TEXT),
            (item["resources"] as? JsonArray).orEmpty().map {
                NovelResourceReference(
                    it.jsonObject.required("htmlReference"),
                    it.jsonObject.required("reference"),
                )
            },
        )
    }

    override suspend fun resourceRequest(reference: String): NovelResourceRequest {
        val uri = URI(reference)
        val allowed =
            (definition.origins + definition.baseUrl).filter(String::isNotBlank).map(::URI)
        require(
            uri.scheme in setOf("http", "https") &&
                uri.userInfo == null &&
                !uri.host.isNullOrBlank() &&
                allowed.any { it.scheme == uri.scheme && it.rawAuthority == uri.rawAuthority },
        ) {
            "Illustration origin is not declared"
        }
        return NovelResourceRequest(uri.toASCIIString())
    }

    private suspend fun run(flow: StudioFlow, reference: String): JsonElement =
        repository
            .execute(
                definition,
                flow,
                buildJsonObject {
                    put("url", reference(reference))
                    put("reference", reference)
                },
                allowDraftScripts = approvedDraftScripts,
                allowDraftCapabilities = draftPreview,
            )
            .value

    private fun entry(item: JsonObject) =
        NovelEntry(
            reference(item.required("url")),
            item.required("title"),
            item.text("cover")?.let(::reference),
            words(item["authors"] ?: item["author"]),
            words(item["aliases"]),
        )

    private fun reference(raw: String): String =
        if (definition.transport == StudioTransport.WEB) {
            URI(definition.baseUrl)
                .resolve(raw)
                .also {
                    require(
                        it.scheme in setOf("http", "https") &&
                            it.userInfo == null &&
                            !it.host.isNullOrBlank(),
                    )
                }
                .toASCIIString()
        } else {
            raw.also { require(it.isNotBlank() && it.length <= 8192) }
        }

    private fun words(raw: JsonElement?): List<String> =
        when (raw) {
            null,
            JsonNull,
            -> emptyList()
            is JsonArray -> raw.map { it.studioText() }.filter(String::isNotBlank)
            else -> raw.studioText().takeIf(String::isNotBlank)?.let(::listOf).orEmpty()
        }
}

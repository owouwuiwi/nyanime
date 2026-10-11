@file:Suppress("OVERRIDE_DEPRECATION")

package it.lorelore.nyanime.studio

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaHomeMetadata
import eu.kanade.tachiyomi.source.model.SMangaTrackingMetadata
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import rx.schedulers.Schedulers
import tachiyomi.domain.discovery.SourceHomePresentation
import tachiyomi.domain.search.withSearchAliases
import java.net.URI

interface StudioHomeProvider {
    val definition: StudioSource
}

/** Both adapters consume the normal source APIs; playback, library and downloads stay unchanged. */
class StudioAnimeSource(
    override val definition: StudioSource,
    repository: SourceStudioRepository,
) : AnimeHttpSource(), StudioHomeProvider {
    private val engine = StudioSourceEngine(definition, repository)
    override val id get() = definition.sourceId
    override val name get() = definition.name
    override val lang get() = definition.language
    override val baseUrl get() = definition.baseUrl
    override val supportsLatest get() = StudioFlow.LATEST in definition.flows
    override fun getFilterList() = AnimeFilterList(
        buildList {
            if (definition.homeFlows.isNotEmpty()) {
                add(object : AnimeFilter.Select<String>(
                    StudioBlueprints.HOME_FILTER,
                    arrayOf("") + definition.homeFlows.keys,
                ) {})
            }
            definition.filters.forEach { field ->
                when (field.kind) {
                    "text" -> add(object : AnimeFilter.Text(field.title, field.initial) {})
                    "multiple" -> add(object : AnimeFilter.Group<AnimeFilter.CheckBox>(
                        field.title,
                        field.options.map {
                            object : AnimeFilter.CheckBox(
                                it,
                                it == field.initial,
                            ) {}
                        },
                    ) {})
                    else -> add(
                        object : AnimeFilter.Select<String>(
                            field.title,
                            field.options.toTypedArray(),
                            field.options.indexOf(field.initial).coerceAtLeast(0),
                        ) {},
                    )
                }
            }
        },
    )
    private fun filters(filters: AnimeFilterList) = filters.associate { filter ->
        filter.name to when (filter) {
            is AnimeFilter.Select<*> -> filter.values.getOrNull(filter.state)?.toString().orEmpty()
            is AnimeFilter.Group<*> -> filter.state.filterIsInstance<AnimeFilter.CheckBox>().filter {
                it.state
            }.joinToString(",") { it.name }
            else -> filter.state.toString()
        }
    }
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val result = engine.list(StudioFlow.SEARCH, page, query, filters(filters))
        return AnimesPage(result.items.map { anime(it) }, result.hasNext)
    }
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val result = engine.list(
            if (StudioFlow.POPULAR in
                definition.flows
            ) {
                StudioFlow.POPULAR
            } else {
                StudioFlow.SEARCH
            },
            page,
        )
        return AnimesPage(result.items.map { anime(it) }, result.hasNext)
    }
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val result = engine.list(StudioFlow.LATEST, page)
        return AnimesPage(result.items.map { anime(it) }, result.hasNext)
    }
    override suspend fun getAnimeDetails(anime: SAnime): SAnime = anime(engine.detail(anime.url), true)
    override suspend fun getEpisodeList(
        anime: SAnime,
    ): List<SEpisode> = engine.items(StudioFlow.ENTRIES, anime.url).map { item ->
        SEpisode.create().apply {
            url = engine.entryUrl(item.required("url"))
            name = item.required("title")
            episode_number = item["number"]?.jsonPrimitive?.floatOrNull ?: -1f
            date_upload = item["uploaded"]?.jsonPrimitive?.longOrNull ?: 0
            scanlator = item.text("group")
            summary = item.text("summary")
            preview_url = item.text("preview")?.let(engine::resolve)
        }
    }
    override suspend fun getVideoList(
        episode: SEpisode,
    ): List<Video> = engine.items(StudioFlow.CONTENT, episode.url).map { item ->
        Video(
            videoUrl = engine.resolve(item.required("url")),
            videoTitle = item.text("title").orEmpty(),
            resolution = item["resolution"]?.jsonPrimitive?.intOrNull,
            headers = item["headers"]?.let { values ->
                Headers.Builder().apply {
                    values.jsonObject.forEach { (key, value) ->
                        require(!key.equals("Host", true) && !key.equals("Content-Length", true))
                        add(key, value.studioText())
                    }
                }.build()
            },
            subtitleTracks = engine.tracks(item["subtitles"]),
            audioTracks = engine.tracks(item["audio"]),
            initialized = true,
        )
    }
    override suspend fun getSeasonList(anime: SAnime): List<SAnime> = emptyList()
    override fun fetchPopularAnime(page: Int) = studioObservable { getPopularAnime(page) }
    override fun fetchLatestUpdates(page: Int) = studioObservable { getLatestUpdates(page) }
    override fun fetchSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ) = studioObservable { getSearchAnime(page, query, filters) }
    override fun fetchAnimeDetails(anime: SAnime) = studioObservable { getAnimeDetails(anime) }
    override fun fetchEpisodeList(anime: SAnime) = studioObservable { getEpisodeList(anime) }
    override fun fetchVideoList(episode: SEpisode) = studioObservable { getVideoList(episode) }
    private fun anime(item: JsonObject, details: Boolean = false) = SAnime.create().apply {
        url = engine.entryUrl(item.required("url"))
        title = item.required("title")
        thumbnail_url = item.text("cover")?.let(engine::resolve)
        background_url =
            item.text("background")?.let(engine::resolve)
        description = item.text("description")
        genre = engine.words(item["genres"]).joinToString(", ")
        author = item.text("author")
        artist = item.text("artist")
        status =
            item["status"]?.jsonPrimitive?.intOrNull ?: 0
        memo = engine.metadata(item)
        initialized = details
    }
}

class StudioMangaSource(
    override val definition: StudioSource,
    repository: SourceStudioRepository,
) : HttpSource(), StudioHomeProvider {
    private val engine = StudioSourceEngine(definition, repository)
    override val id get() = definition.sourceId
    override val name get() = definition.name
    override val lang get() = definition.language
    override val baseUrl get() = definition.baseUrl
    override val supportsLatest get() = StudioFlow.LATEST in definition.flows
    override fun getFilterList() = FilterList(
        buildList {
            if (definition.homeFlows.isNotEmpty()) {
                add(object : Filter.Select<String>(
                    StudioBlueprints.HOME_FILTER,
                    arrayOf("") + definition.homeFlows.keys,
                ) {})
            }
            definition.filters.forEach { field ->
                when (field.kind) {
                    "text" -> add(object : Filter.Text(field.title, field.initial) {})
                    "multiple" -> add(object : Filter.Group<Filter.CheckBox>(
                        field.title,
                        field.options.map {
                            object : Filter.CheckBox(
                                it,
                                it == field.initial,
                            ) {}
                        },
                    ) {})
                    else -> add(
                        object : Filter.Select<String>(
                            field.title,
                            field.options.toTypedArray(),
                            field.options.indexOf(field.initial).coerceAtLeast(0),
                        ) {},
                    )
                }
            }
        },
    )
    private fun filters(filters: FilterList) = filters.associate { filter ->
        filter.name to when (filter) {
            is Filter.Select<*> -> filter.values.getOrNull(filter.state)?.toString().orEmpty()
            is Filter.Group<*> -> filter.state.filterIsInstance<Filter.CheckBox>().filter {
                it.state
            }.joinToString(",") { it.name }
            else -> filter.state.toString()
        }
    }
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val result = engine.list(StudioFlow.SEARCH, page, query, filters(filters))
        return MangasPage(result.items.map { manga(it) }, result.hasNext)
    }
    override suspend fun getPopularManga(page: Int): MangasPage {
        val result = engine.list(
            if (StudioFlow.POPULAR in
                definition.flows
            ) {
                StudioFlow.POPULAR
            } else {
                StudioFlow.SEARCH
            },
            page,
        )
        return MangasPage(result.items.map { manga(it) }, result.hasNext)
    }
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val result = engine.list(StudioFlow.LATEST, page)
        return MangasPage(result.items.map { manga(it) }, result.hasNext)
    }
    override suspend fun getMangaDetails(manga: SManga): SManga = manga(engine.detail(manga.url), true)
    override suspend fun getChapterList(
        manga: SManga,
    ): List<SChapter> = engine.items(StudioFlow.ENTRIES, manga.url).map { item ->
        SChapter.create().apply {
            url = engine.entryUrl(item.required("url"))
            name = item.required("title")
            chapter_number = item["number"]?.jsonPrimitive?.floatOrNull ?: -1f
            date_upload = item["uploaded"]?.jsonPrimitive?.longOrNull ?: 0
            scanlator = item.text("group")
        }
    }
    override suspend fun getPageList(
        chapter: SChapter,
    ): List<Page> = engine.items(StudioFlow.CONTENT, chapter.url).mapIndexed { index, item ->
        Page(index, imageUrl = engine.resolve(item.required("url")))
    }
    override suspend fun getImageUrl(page: Page): String = requireNotNull(page.imageUrl) { "Missing page address" }
    override fun fetchPopularManga(page: Int) = studioObservable { getPopularManga(page) }
    override fun fetchLatestUpdates(page: Int) = studioObservable { getLatestUpdates(page) }
    override fun fetchSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ) = studioObservable { getSearchManga(page, query, filters) }
    override fun fetchMangaDetails(manga: SManga) = studioObservable { getMangaDetails(manga) }
    override fun fetchChapterList(manga: SManga) = studioObservable { getChapterList(manga) }
    override fun fetchPageList(chapter: SChapter) = studioObservable { getPageList(chapter) }
    private fun manga(item: JsonObject, details: Boolean = false) = SManga.create().apply {
        url = engine.entryUrl(item.required("url"))
        title = item.required("title")
        thumbnail_url = item.text("cover")?.let(engine::resolve)
        description = item.text("description")
        genre = engine.words(item["genres"]).joinToString(", ")
        author = item.text("author")
        artist =
            item.text("artist")
        status = item["status"]?.jsonPrimitive?.intOrNull ?: 0
        memo = engine.metadata(item)
        (this as? SMangaHomeMetadata)?.homePresentation = item["presentation"]?.toString() ?: buildJsonObject {
            put("id", item["id"] ?: JsonPrimitive(url))
            put("badges", JsonArray(engine.words(item["badges"]).map(::JsonPrimitive)))
            put("details", JsonArray(engine.words(item["details"]).map(::JsonPrimitive)))
            item["rank"]?.let { put("rank", it) }
            item["chapters"]?.let { put("chapters", it) }
            item["sectionTitle"]?.let { put("sectionTitle", it) }
            put("catalogIds", engine.ids(item))
        }.toString()
        (this as? SMangaTrackingMetadata)?.trackingMetadata = memo["nyanime.tracking.v1"]?.toString()
        initialized = details
    }

    // Legacy parser entry points cannot bypass the graph executor.
    override fun popularMangaRequest(page: Int): Request = error("Use the source operation")
    override fun popularMangaParse(response: Response): MangasPage = error("Use the source operation")
    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request = error("Use the source operation")
    override fun searchMangaParse(response: Response): MangasPage = error("Use the source operation")
    override fun latestUpdatesRequest(page: Int): Request = error("Use the source operation")
    override fun latestUpdatesParse(response: Response): MangasPage = error("Use the source operation")
    override fun mangaDetailsParse(response: Response): SManga = error("Use the source operation")
    override fun chapterListParse(response: Response): List<SChapter> = error("Use the source operation")
    override fun pageListParse(response: Response): List<Page> = error("Use the source operation")
    override fun imageUrlParse(response: Response): String = error("Use the source operation")
}

private fun <T> studioObservable(block: suspend () -> T): Observable<T> = Observable.fromCallable {
    runBlocking(Dispatchers.IO) { block() }
}.subscribeOn(Schedulers.io())
private fun JsonObject.text(key: String) = this[key]?.studioText()?.takeIf { it.isNotBlank() }
private fun JsonObject.required(key: String) = requireNotNull(text(key)) { "Missing result field: $key" }

internal class StudioSourceEngine(
    private val definition: StudioSource,
    private val repository: SourceStudioRepository,
) {
    data class PageResult(val items: List<JsonObject>, val hasNext: Boolean)
    private val cursors = object : LinkedHashMap<String, String>(64, .75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, String>?,
        ) =
            size > 128
    }
    fun resolve(value: String): String = URI(definition.baseUrl).resolve(value).also {
        require(it.scheme in setOf("https", "http") && !it.host.isNullOrBlank() && it.userInfo == null) {
            "Unsupported content address"
        }
    }.toASCIIString()
    fun entryUrl(value: String): String {
        val resolved = URI(resolve(value))
        val base = URI(definition.baseUrl)
        return if (resolved.authority == base.authority &&
            resolved.scheme == base.scheme
        ) {
            resolved.rawPath.orEmpty() + (resolved.rawQuery?.let { "?$it" } ?: "")
        } else {
            resolved.toASCIIString()
        }
    }
    fun words(
        value: JsonElement?,
    ): List<String> = when (value) {
        is JsonArray -> value.map { it.studioText() }
        null, JsonNull -> emptyList()
        else -> value.studioText().split('\n').filter { it.isNotBlank() }
    }
    fun ids(item: JsonObject): JsonObject = buildJsonObject {
        mapOf(
            "anilistId" to "anilist",
            "malId" to "myanimelist",
            "mangaUpdatesId" to "mangaupdates",
        ).forEach { (field, catalog) ->
            item[field]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }?.let { put(catalog, it) }
        }
    }
    fun metadata(item: JsonObject): JsonObject {
        val memo = withSearchAliases(item["memo"] as? JsonObject ?: JsonObject(emptyMap()), words(item["aliases"]))
        val catalogIds = ids(item)
        val tracking =
            buildJsonObject {
                put("ids", catalogIds)
                put("titles", JsonArray(words(item["aliases"]).map(::JsonPrimitive)))
            }
        val presentation = SourceHomePresentation.from(memo) ?: SourceHomePresentation(
            id = item.text("id"),
            badges = words(item["badges"]),
            details = words(item["details"]),
            sectionTitle = item.text("sectionTitle"),
            episodeTarget = item.text("episodeTarget"),
            rank = item["rank"]?.jsonPrimitive?.intOrNull,
            catalogIds = catalogIds.mapValues { it.value.jsonPrimitive.long },
            aliases = words(item["aliases"]),
        )
        return presentation.attachTo(JsonObject(memo + ("nyanime.tracking.v1" to tracking)))
    }
    fun tracks(
        value: JsonElement?,
    ): List<Track> = (value as? JsonArray).orEmpty().map {
        it.jsonObject.let { track -> Track(resolve(track.required("url")), track.required("language")) }
    }
    suspend fun list(
        flow: StudioFlow,
        page: Int,
        query: String = "",
        filters: Map<String, String> = emptyMap(),
    ): PageResult {
        require(page in 1..10_000)
        val section = filters[StudioBlueprints.HOME_FILTER]?.takeIf { it.isNotBlank() }
        val graph = section?.let { requireNotNull(definition.homeFlows[it]) { "Home flow no longer available" } }
        val key = "$flow:$section:$query:${filters.toSortedMap()}"
        val cursor = synchronized(cursors) {
            if (page ==
                1
            ) {
                cursors.keys.removeAll { it.startsWith("$key:") }
                ""
            } else {
                cursors["$key:$page"].orEmpty()
            }
        }
        val variables = buildJsonObject {
            put("query", query)
            put("page", page)
            put("cursor", cursor)
            put("url", definition.baseUrl)
            definition.filters.forEach { field ->
                put("filter.${field.id}", StudioAssist.filterValue(field, filters[field.title] ?: field.initial))
            }
            put("section", section.orEmpty())
        }
        val result = repository.execute(definition, flow, variables, overrideGraph = graph).value
        val raw = if (result is JsonObject) result["items"] ?: error("Return items in a paginated result") else result
        val items = raw.jsonArray.map { it.jsonObject }
        val next = (result as? JsonObject)?.get("hasNext")?.jsonPrimitive?.booleanOrNull ?: false
        val nextCursor = (result as? JsonObject)?.get("cursor")?.studioText().orEmpty()
        synchronized(cursors) { if (nextCursor.isNotBlank()) cursors["$key:${page + 1}"] = nextCursor }
        return PageResult(items, next)
    }
    suspend fun items(flow: StudioFlow, url: String): List<JsonObject> {
        val result = repository.execute(
            definition,
            flow,
            buildJsonObject {
                put("url", resolve(url))
                put("baseUrl", definition.baseUrl)
            },
        ).value
        return result.jsonArray.map { it.jsonObject }
    }
    suspend fun detail(url: String): JsonObject {
        val result = repository.execute(
            definition,
            StudioFlow.DETAILS,
            buildJsonObject {
                put("url", resolve(url))
            },
        ).value
        val item = if (result is JsonArray) result.single().jsonObject else result.jsonObject
        return JsonObject(
            mapOf("url" to JsonPrimitive(url)) +
                item.filterValues {
                    it != JsonNull && it.studioText().isNotBlank()
                },
        )
    }
}

package eu.kanade.tachiyomi.data.workinfo

import eu.kanade.tachiyomi.data.discovery.obj
import eu.kanade.tachiyomi.data.discovery.objects
import eu.kanade.tachiyomi.data.discovery.text
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException

/** Structured CC0 film information. IMDb is an outbound verified identifier, never scraped. */
class WikidataWorkInformation(private val http: WorkInfoHttp) : WorkInformationProvider {
    override val provider = WorkProvider.WIKIDATA

    override suspend fun search(identity: WorkIdentity, language: String): List<WorkSummary> {
        val ids = if (identity.imdbId?.matches(Regex("tt[0-9]{7,12}")) == true) {
            query("SELECT ?item WHERE { ?item wdt:P345 \"${identity.imdbId}\"; wdt:P31/wdt:P279* wd:Q11424. } LIMIT 10")
                .mapNotNull { it.obj("item").text("value")?.substringAfterLast('/') }
        } else {
            val found = api(
                "action" to "wbsearchentities",
                "search" to identity.title.take(256),
                "language" to language,
                "uselang" to language,
                "limit" to "10",
                "type" to "item",
            ).objects("search").mapNotNull { it.text("id") }.filter(::validId)
            if (found.isEmpty()) return emptyList()
            query(
                "SELECT DISTINCT ?item WHERE { VALUES ?item { ${found.joinToString(" ") { "wd:$it" }} } " +
                    "?item wdt:P31/wdt:P279* wd:Q11424. } LIMIT 10",
            ).mapNotNull { it.obj("item").text("value")?.substringAfterLast('/') }
        }
        return entities(ids, language).values.mapNotNull { summary(it, language) }
    }

    override suspend fun information(reference: WorkReference, language: String): WorkInformation {
        require(reference.isValid() && reference.provider == provider)
        if (reference.id !in filmIds(listOf(reference.id))) throw IOException("Catalogue identity is not a film")
        val entity =
            entities(listOf(reference.id), language)[reference.id] ?: throw IOException("Missing catalogue work")
        val work = summary(entity, language) ?: throw IOException("Invalid catalogue work")
        val properties = listOf("P272", "P495", "P364", "P155", "P156", "P144", "P179")
        val creators = creatorIds(entity)
        val cast = ids(entity, "P161")
        val linked = (properties.flatMap { ids(entity, it) } + creators.take(12).map { it.first } + cast.take(8))
            .distinct()
        val names = entities(linked, language)
        val relatedFilms = filmIds(ids(entity, "P155") + ids(entity, "P156"))
        fun labels(
            property: String,
        ) = ids(entity, property).mapNotNull { names[it]?.let { value -> label(value, language) } }
        fun credits(people: List<Pair<String, String>>) = people.mapNotNull { (id, role) ->
            names[id]?.let { label(it, language) }?.let { WorkCredit(it, role, WorkPersonReference(provider, id)) }
        }
        val runtime = values(entity, "P2047").filterIsInstance<JsonObject>().firstOrNull()
        val duration = runtime?.text("amount")?.toDoubleOrNull()
        val minutes = when (runtime?.text("unit")?.substringAfterLast('/')) {
            "Q7727" -> duration?.toInt() // minute
            "Q11574" -> duration?.div(60)?.toInt() // second
            "Q25235" -> duration?.times(60)?.toInt() // hour
            else -> null
        }
        return WorkInformation(
            work = work, textLanguage = language,
            // Wikidata's short entity description is not a plot; do not substitute it for a synopsis.
            originalTitle = values(entity, "P1476").filterIsInstance<JsonObject>().firstOrNull()?.text("text"),
            countries = labels("P495"), languages = labels("P364"), minutes = minutes,
            studios = labels("P272"), creators = credits(creators.take(12)),
            collections = labels("P179"), adaptedFrom = labels("P144"),
            cast = credits(cast.take(8).map { it to "CAST" }),
            moreCreators = creators.size > 12,
            moreCast = cast.size > 8,
            imdbId = strings(entity, "P345").firstOrNull(),
            relations = listOf("P155" to "PREQUEL", "P156" to "SEQUEL").flatMap { (property, relation) ->
                ids(entity, property).filter { it in relatedFilms }.mapNotNull { id ->
                    names[id]?.let { summary(it, language) }?.let { WorkRelation(it, relation) }
                }
            },
        )
    }

    override suspend fun person(reference: WorkPersonReference, language: String): WorkPerson {
        val entity =
            entities(listOf(reference.id), language)[reference.id] ?: throw IOException("Missing catalogue person")
        val description = localized(entity.obj("descriptions"), language)
        return WorkPerson(
            reference,
            label(entity, language) ?: throw IOException("Missing person name"),
            description?.text("value"),
            description?.text("language") ?: language,
        )
    }

    override suspend fun works(reference: WorkPersonReference, page: Int, language: String): WorkPage {
        require(reference.isValid())
        val rows = query(
            "SELECT DISTINCT ?item ?role WHERE { VALUES ?role { wdt:P57 wdt:P58 wdt:P161 } " +
                "?item ?role wd:${reference.id}; wdt:P31/wdt:P279* wd:Q11424. } " +
                "ORDER BY ?item ?role LIMIT 21 OFFSET ${(page - 1) * 20}",
        )
        val names =
            entities(rows.take(20).mapNotNull { it.obj("item").text("value")?.substringAfterLast('/') }, language)
        return WorkPage(
            rows.take(20).mapNotNull { row ->
                val id = row.obj("item").text("value")?.substringAfterLast('/') ?: return@mapNotNull null
                val role = when (row.obj("role").text("value")?.substringAfterLast('/')) {
                    "P57" -> "DIRECTOR"
                    "P58" -> "SCREENPLAY"
                    else -> "CAST"
                }
                names[id]?.let { summary(it, language) }?.let { WorkRelation(it, role) }
            },
            rows.size > 20,
        )
    }

    override suspend fun credits(
        reference: WorkReference,
        cast: Boolean,
        page: Int,
        language: String,
    ): WorkCreditsPage {
        require(reference.isValid() && reference.provider == provider)
        val entity = entities(listOf(reference.id), language)[reference.id]
            ?: throw IOException("Missing catalogue work")
        val people = if (cast) ids(entity, "P161").map { it to "CAST" } else creatorIds(entity)
        val visible = people.drop((page - 1) * 20).take(20)
        val names = entities(visible.map { it.first }, language)
        return WorkCreditsPage(
            visible.mapNotNull { (id, role) ->
                names[id]?.let { label(it, language) }?.let {
                    WorkCredit(it, role, WorkPersonReference(provider, id))
                }
            },
            people.size > page * 20,
        )
    }

    private fun creatorIds(entity: JsonObject) = ids(entity, "P57").map { it to "DIRECTOR" } +
        ids(entity, "P58").map { it to "SCREENPLAY" }

    private suspend fun entities(ids: List<String>, language: String): Map<String, JsonObject> {
        val result = mutableMapOf<String, JsonObject>()
        for (chunk in ids.filter(::validId).distinct().take(200).chunked(50)) {
            val values = api(
                "action" to "wbgetentities",
                "ids" to chunk.joinToString("|"),
                "props" to "labels|descriptions|claims|aliases",
                "languages" to "$language|en",
                "languagefallback" to "1",
            ).obj("entities")
            values.forEach { (key, value) -> if (value is JsonObject) result[key] = value }
        }
        return result
    }

    private suspend fun filmIds(ids: List<String>): Set<String> {
        val valid = ids.filter(::validId).distinct().take(20)
        if (valid.isEmpty()) return emptySet()
        return query(
            "SELECT DISTINCT ?item WHERE { VALUES ?item { ${valid.joinToString(" ") { "wd:$it" }} } " +
                "?item wdt:P31/wdt:P279* wd:Q11424. } LIMIT 20",
        ).mapNotNull { it.obj("item").text("value")?.substringAfterLast('/') }.toSet()
    }

    private suspend fun api(vararg parameters: Pair<String, String>): JsonObject = (
        http.get(
            "https://www.wikidata.org/w/api.php".toHttpUrl().newBuilder().apply {
                addQueryParameter("format", "json")
                parameters.forEach { (key, value) -> addQueryParameter(key, value) }
            }.build(),
        ) as? JsonObject ?: throw IOException("Invalid catalogue response")
        ).also {
        if ("error" in it) throw IOException("Catalogue rejected query")
    }

    private suspend fun query(query: String): List<JsonObject> {
        val result = (
            http.get(
                "https://query.wikidata.org/sparql".toHttpUrl().newBuilder()
                    .addQueryParameter("format", "json").addQueryParameter("query", query).build(),
            ) as? JsonObject
            )?.obj("results") ?: throw IOException("Invalid catalogue query")
        if ("bindings" !in result) throw IOException("Catalogue query failed")
        return result.objects("bindings")
    }

    internal fun summary(entity: JsonObject, language: String): WorkSummary? {
        val id = entity.text("id")?.takeIf(::validId) ?: return null
        val year = values(entity, "P577").filterIsInstance<JsonObject>()
            .mapNotNull { it.text("time")?.removePrefix("+")?.take(4)?.toIntOrNull() }.minOrNull()
        return WorkSummary(
            WorkReference(provider, id, WorkKind.FILM),
            label(entity, language) ?: return null,
            entity.obj("aliases").objects(language).mapNotNull { it.text("value") },
            year,
            "FILM",
        )
    }

    private fun label(entity: JsonObject, language: String) = localized(entity.obj("labels"), language)?.text("value")
    private fun localized(values: JsonObject, language: String) = (values[language] ?: values["en"]) as? JsonObject
    private fun ids(entity: JsonObject, property: String) = values(entity, property).filterIsInstance<JsonObject>()
        .mapNotNull { it.text("id") }.filter(::validId)
    private fun strings(
        entity: JsonObject,
        property: String,
    ) = values(entity, property).filterIsInstance<JsonPrimitive>()
        .mapNotNull { it.contentOrNull }
    private fun values(entity: JsonObject, property: String): List<JsonElement> = entity.obj("claims").objects(property)
        .filter { it.text("rank") != "deprecated" }
        .mapNotNull { it.obj("mainsnak").obj("datavalue")["value"] }
    private fun validId(id: String) = Regex("Q[1-9][0-9]{0,11}").matches(id)
}

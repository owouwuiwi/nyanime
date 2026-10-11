package eu.kanade.tachiyomi.data.workinfo

import eu.kanade.tachiyomi.data.discovery.AnilistCatalogTransport
import eu.kanade.tachiyomi.data.discovery.number
import eu.kanade.tachiyomi.data.discovery.obj
import eu.kanade.tachiyomi.data.discovery.objects
import eu.kanade.tachiyomi.data.discovery.strings
import eu.kanade.tachiyomi.data.discovery.text
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jsoup.Jsoup
import java.io.IOException

class AniListWorkInformation(private val transport: AnilistCatalogTransport) : WorkInformationProvider {
    override val provider = WorkProvider.ANILIST

    override suspend fun search(identity: WorkIdentity, language: String): List<WorkSummary> {
        val type = if (identity.kind == WorkKind.MANGA) "MANGA" else "ANIME"
        if (identity.malId != null && identity.malId in 1..Int.MAX_VALUE) {
            val result = transport.execute(
                "query(\$id: Int, \$type: MediaType) { Media(idMal: \$id, type: \$type) { $SUMMARY } }",
                buildJsonObject {
                    put("id", identity.malId)
                    put("type", type)
                },
            ).obj("Media")
            return listOfNotNull(summary(result))
        }
        return transport.execute(
            "query(\$q: String, \$type: MediaType) { Page(page: 1, perPage: 10) { " +
                "media(search: \$q, type: \$type) { $SUMMARY } } }",
            buildJsonObject {
                put("q", identity.title.take(256))
                put("type", type)
            },
        ).obj("Page").objects("media").mapNotNull(::summary)
    }

    override suspend fun information(reference: WorkReference, language: String): WorkInformation {
        val media = transport.execute(
            "query(\$id: Int, \$type: MediaType) { Media(id: \$id, type: \$type) { $SUMMARY " +
                "description(asHtml: true) status countryOfOrigin duration episodes chapters volumes " +
                "studios(isMain: true) { nodes { name } } " +
                "staff(page: 1, perPage: 12, sort: RELEVANCE) { $STAFF } " +
                "characters(page: 1, perPage: 8, sort: ROLE) { $CHARACTERS } " +
                "relations { edges { relationType node { $SUMMARY } } } } }",
            variables(reference),
        ).obj("Media")
        val work =
            summary(media)?.takeIf { it.reference == reference } ?: throw IOException("Invalid catalogue identity")
        return WorkInformation(
            work = work,
            synopsis = plain(media.text("description")),
            originalTitle = media.obj("title").text("native"),
            status = media.text("status"),
            countries = listOfNotNull(media.text("countryOfOrigin")),
            minutes = media.number("duration"),
            episodes = media.number("episodes"),
            chapters = media.number("chapters"),
            volumes = media.number("volumes"),
            studios = media.obj("studios").objects("nodes").mapNotNull { it.text("name") },
            creators = staff(media.obj("staff")),
            cast = cast(media.obj("characters")),
            moreCreators = hasNext(media.obj("staff")),
            moreCast = hasNext(media.obj("characters")),
            relations = media.obj("relations").objects("edges").mapNotNull { edge ->
                summary(edge.obj("node"))?.let { WorkRelation(it, edge.text("relationType") ?: "RELATED") }
            },
        )
    }

    override suspend fun person(reference: WorkPersonReference, language: String): WorkPerson {
        val staff = transport.execute(
            "query(\$id: Int) { Staff(id: \$id) { id name { full } description(asHtml: true) } }",
            buildJsonObject { put("id", reference.id.toInt()) },
        ).obj("Staff")
        if (staff.number("id")?.toString() != reference.id) throw IOException("Invalid catalogue person")
        return WorkPerson(reference, staff.obj("name").text("full").orEmpty(), plain(staff.text("description")))
    }

    override suspend fun works(reference: WorkPersonReference, page: Int, language: String): WorkPage {
        val data = transport.execute(
            "query(\$id: Int, \$page: Int) { Staff(id: \$id) { " +
                "staffMedia(page: \$page, perPage: 20, sort: START_DATE_DESC) { " +
                "pageInfo { hasNextPage } edges { staffRole node { $SUMMARY } } } " +
                "characterMedia(page: \$page, perPage: 20, sort: START_DATE_DESC) { " +
                "pageInfo { hasNextPage } edges { node { $SUMMARY } } } } }",
            buildJsonObject {
                put("id", reference.id.toInt())
                put("page", page)
            },
        ).obj("Staff")
        val staff = data.obj("staffMedia")
        val characters = data.obj("characterMedia")
        return WorkPage(
            staff.objects("edges").mapNotNull { edge ->
                summary(edge.obj("node"))?.let { WorkRelation(it, edge.text("staffRole").orEmpty()) }
            } +
                characters.objects("edges").mapNotNull { edge ->
                    summary(edge.obj("node"))?.let { WorkRelation(it, "VOICE") }
                },
            hasNext(staff) || hasNext(characters),
        )
    }

    override suspend fun credits(
        reference: WorkReference,
        cast: Boolean,
        page: Int,
        language: String,
    ): WorkCreditsPage {
        val field = if (cast) "characters" else "staff"
        val fields = if (cast) CHARACTERS else STAFF
        val media = transport.execute(
            "query(\$id: Int, \$type: MediaType, \$page: Int) { Media(id: \$id, type: \$type) { " +
                "$field(page: \$page, perPage: 20, sort: ${if (cast) "ROLE" else "RELEVANCE"}) { $fields } } }",
            buildJsonObject {
                put("id", reference.id.toInt())
                put("type", reference.kind.name)
                put("page", page)
            },
        ).obj("Media").obj(field)
        return WorkCreditsPage(if (cast) cast(media) else staff(media), hasNext(media))
    }

    private fun variables(reference: WorkReference) = buildJsonObject {
        require(reference.isValid() && reference.provider == provider)
        put("id", reference.id.toInt())
        put("type", reference.kind.name)
    }

    internal fun summary(value: JsonObject): WorkSummary? {
        val id = value.number("id")?.takeIf { it > 0 } ?: return null
        val kind = when (value.text("type")) {
            "ANIME" -> WorkKind.ANIME
            "MANGA" -> WorkKind.MANGA
            else -> return null
        }
        val title = value.obj("title")
        val names =
            listOfNotNull(title.text("romaji"), title.text("english"), title.text("native")) + value.strings("synonyms")
        return WorkSummary(
            WorkReference(provider, id.toString(), kind),
            names.firstOrNull() ?: return null,
            names.distinct(),
            value.obj("startDate").number("year"),
            value.text("format"),
            malId = value.number("idMal")?.toLong(),
        )
    }

    private fun staff(value: JsonObject) = value.objects("edges").mapNotNull { edge ->
        val node = edge.obj("node")
        WorkCredit(
            node.obj("name").text("full") ?: return@mapNotNull null,
            edge.text("role").orEmpty(),
            node.number("id")?.let { WorkPersonReference(provider, it.toString()) },
        )
    }

    private fun cast(value: JsonObject) = value.objects("edges").flatMap { edge ->
        val character = edge.obj("node").obj("name").text("full") ?: return@flatMap emptyList()
        val actors = edge.objects("voiceActors")
        if (actors.isEmpty()) return@flatMap listOf(WorkCredit(character, edge.text("role").orEmpty()))
        actors.mapNotNull { actor ->
            WorkCredit(
                actor.obj("name").text("full") ?: return@mapNotNull null,
                "VOICE",
                actor.number("id")?.let { WorkPersonReference(provider, it.toString()) },
                character,
                actor.text("languageV2"),
            )
        }
    }

    private fun hasNext(value: JsonObject) = value.obj("pageInfo").text("hasNextPage") == "true"

    companion object {
        private const val SUMMARY = "id idMal type title { romaji english native } synonyms startDate { year } format"
        private const val STAFF = "pageInfo { hasNextPage } edges { role node { id name { full } } }"
        private const val CHARACTERS = "pageInfo { hasNextPage } edges { role node { name { full } } " +
            "voiceActors { id name { full } languageV2 } }"
        internal fun plain(value: String?) = value?.let { Jsoup.parse(it).text().trim().takeIf(String::isNotEmpty) }
    }
}

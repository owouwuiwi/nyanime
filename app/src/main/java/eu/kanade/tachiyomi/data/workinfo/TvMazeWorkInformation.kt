package eu.kanade.tachiyomi.data.workinfo

import eu.kanade.tachiyomi.data.discovery.number
import eu.kanade.tachiyomi.data.discovery.obj
import eu.kanade.tachiyomi.data.discovery.objects
import eu.kanade.tachiyomi.data.discovery.text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException

class TvMazeWorkInformation(private val http: WorkInfoHttp) : WorkInformationProvider {
    override val provider = WorkProvider.TVMAZE

    override suspend fun search(identity: WorkIdentity, language: String): List<WorkSummary> {
        if (identity.imdbId?.matches(Regex("tt[0-9]{7,12}")) == true) {
            return try {
                val result = http.get(url("lookup/shows", "imdb" to identity.imdbId)) as? JsonObject
                listOfNotNull(result?.let(::summary))
            } catch (error: WorkCatalogueHttpException) {
                if (error.status == 404) emptyList() else throw error
            }
        }
        return array("search/shows", "q" to identity.title.take(256)).mapNotNull { summary(it.obj("show")) }
    }

    override suspend fun information(reference: WorkReference, language: String): WorkInformation {
        require(reference.isValid() && reference.provider == provider)
        val show = http.get(
            url(
                "shows/${reference.id}",
                "embed[]" to "cast",
                "embed[]" to "crew",
                "embed[]" to "seasons",
                "embed[]" to "akas",
            ),
        ) as? JsonObject
            ?: throw IOException("Invalid catalogue response")
        val work =
            summary(show)?.takeIf { it.reference == reference } ?: throw IOException("Invalid catalogue identity")
        val embedded = show.obj("_embedded")
        val crew = embedded.objects("crew")
        val seasons = embedded.objects("seasons")
        return WorkInformation(
            work = work.copy(aliases = embedded.objects("akas").mapNotNull { it.text("name") }.distinct()),
            synopsis = AniListWorkInformation.plain(show.text("summary")),
            status = show.text("status"),
            countries = listOfNotNull(
                show.obj("network").obj("country").text("code") ?: show.obj("webChannel").obj("country").text("code"),
            ),
            languages = listOfNotNull(show.text("language")),
            minutes = show.number("runtime") ?: show.number("averageRuntime"),
            creators = crew.mapNotNull { credit(it, cast = false) },
            cast = show.obj("_embedded").objects("cast").mapNotNull { credit(it, cast = true) },
            seasons = seasons.mapNotNull { season ->
                WorkSeason(
                    season.number("number") ?: return@mapNotNull null,
                    season.number("episodeOrder"),
                    year(season.text("premiereDate")),
                )
            },
            imdbId = show.obj("externals").text("imdb"),
        )
    }

    override suspend fun person(reference: WorkPersonReference, language: String): WorkPerson {
        val value =
            http.get(url("people/${reference.id}")) as? JsonObject ?: throw IOException("Invalid catalogue person")
        if (value.number("id")?.toString() != reference.id) throw IOException("Invalid catalogue person")
        return WorkPerson(reference, value.text("name").orEmpty())
    }

    override suspend fun works(reference: WorkPersonReference, page: Int, language: String): WorkPage {
        // TVmaze has no cursor for person credits. This endpoint is requested only from the person's page.
        val all = array("people/${reference.id}/castcredits", "embed" to "show").mapNotNull { credit ->
            summary(credit.obj("_embedded").obj("show"))?.let { WorkRelation(it, "CAST") }
        } +
            array("people/${reference.id}/crewcredits", "embed" to "show").mapNotNull { credit ->
                summary(credit.obj("_embedded").obj("show"))?.let { WorkRelation(it, credit.text("type").orEmpty()) }
            }
        val distinct = all.distinctBy { it.work.reference to it.relation }.sortedByDescending { it.work.year }
        return WorkPage(distinct.drop((page - 1) * 20).take(20), distinct.size > page * 20)
    }

    override suspend fun credits(
        reference: WorkReference,
        cast: Boolean,
        page: Int,
        language: String,
    ): WorkCreditsPage {
        val all = array("shows/${reference.id}/${if (cast) "cast" else "crew"}").mapNotNull { credit(it, cast) }
        return WorkCreditsPage(all.drop((page - 1) * 20).take(20), all.size > page * 20)
    }

    internal fun summary(show: JsonObject): WorkSummary? {
        return WorkSummary(
            WorkReference(provider, show.number("id")?.toString() ?: return null, WorkKind.SERIES),
            show.text("name") ?: return null,
            year = year(show.text("premiered")),
            format = "SERIES",
        )
    }

    private fun credit(value: JsonObject, cast: Boolean): WorkCredit? {
        val person = value.obj("person")
        return WorkCredit(
            person.text("name") ?: return null,
            if (cast) "CAST" else value.text("type").orEmpty(),
            person.number("id")?.let { WorkPersonReference(provider, it.toString()) },
            value.obj("character").text("name"),
        )
    }

    private suspend fun array(path: String, vararg parameters: Pair<String, String>): List<JsonObject> {
        val result = http.get(url(path, *parameters)) as? JsonArray ?: throw IOException("Invalid catalogue list")
        return result.filterIsInstance<JsonObject>()
    }

    private fun url(path: String, vararg parameters: Pair<String, String>) =
        "https://api.tvmaze.com/$path".toHttpUrl().newBuilder().apply {
            parameters.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()

    private fun year(value: String?) = value?.take(4)?.toIntOrNull()
}

package it.lorelore.nyanime.themes

import eu.kanade.tachiyomi.data.workinfo.WorkInfoHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException

interface ThemeProvider {
    val origin: ThemeOrigin
    suspend fun themes(identity: ThemeIdentity): List<AnimeTheme>
}

class PartialThemeData(val songs: List<AnimeTheme>) : IOException("Incomplete theme metadata")

class AnimeThemesProvider(private val http: WorkInfoHttp) : ThemeProvider {
    override val origin = ThemeOrigin.ANIMETHEMES

    override suspend fun themes(identity: ThemeIdentity): List<AnimeTheme> {
        require(identity.valid)
        val mal = identity.malId?.takeIf { it > 0 }
        val id = mal ?: identity.anilistId!!
        val url = "https://api.animethemes.moe/anime".toHttpUrl().newBuilder()
            .addQueryParameter("filter[has]", "resources")
            .addQueryParameter("filter[site]", if (mal != null) "MyAnimeList" else "AniList")
            .addQueryParameter("filter[external_id]", id.toString())
            .addQueryParameter(
                "include",
                "resources,animethemes.song.artists,animethemes.song.resources,animethemes.animethemeentries",
            ).build()
        val document = http.get(url)
        if ((document as? JsonObject)?.get("anime") !is JsonArray) throw IOException("Invalid theme catalogue response")
        return parse(document, identity)
    }

    companion object {
        fun parse(document: JsonElement, identity: ThemeIdentity): List<AnimeTheme> {
            // Verify the response as well as the request. An ignored filter must not identify another season.
            val matches = document.items("anime").filter { anime ->
                val resources = anime.items("resources")
                fun ids(site: String) = resources.filter { it.text("site").equals(site, true) }
                    .mapNotNull { it.primitive("external_id")?.longOrNull }.toSet()
                val mal = ids("MyAnimeList")
                val anilist = ids("AniList")
                val expectedMal = identity.malId?.takeIf { it > 0 }
                val expectedAniList = identity.anilistId?.takeIf { it > 0 }
                (expectedMal != null && expectedMal in mal || expectedAniList != null && expectedAniList in anilist) &&
                    (expectedMal == null || mal.isEmpty() || mal == setOf(expectedMal)) &&
                    (expectedAniList == null || anilist.isEmpty() || anilist == setOf(expectedAniList))
            }
            val anime = matches.singleOrNull() ?: return emptyList()
            return anime.items("animethemes").mapNotNull { theme ->
                val kind = when (theme.text("type")) {
                    "OP" -> ThemeKind.OPENING
                    "ED" -> ThemeKind.ENDING
                    else -> return@mapNotNull null
                }
                val song = theme.field("song")
                val title = song.text("title")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val versions = theme.items("animethemeentries").map { entry ->
                    ThemeVersion(
                        entry.primitive("version")?.intOrNull,
                        entry.text("episodes"),
                        entry.flag("spoiler") || entry.flag("nsfw"),
                    )
                }
                val videos = song.items("resources").mapNotNull { resource ->
                    if (resource.text("site") !in setOf("YouTube", "YouTube Music")) return@mapNotNull null
                    val id = ThemeVideoIds.fromUrl(resource.text("link")) ?: return@mapNotNull null
                    ThemeVideo(
                        id,
                        if (resource.text("site") == "YouTube Music") ThemeVideoKind.FULL else ThemeVideoKind.UNKNOWN,
                        ThemeOrigin.ANIMETHEMES,
                        title,
                        spoiler = versions.any { it.spoiler },
                    )
                }
                AnimeTheme(
                    kind,
                    theme.primitive("sequence")?.intOrNull?.takeIf { it > 0 } ?: 1,
                    title,
                    song.items("artists").mapNotNull { it.text("name") },
                    versions,
                    videos,
                )
            }
        }
    }
}

class JikanThemesProvider(private val http: WorkInfoHttp) : ThemeProvider {
    override val origin = ThemeOrigin.JIKAN

    override suspend fun themes(identity: ThemeIdentity): List<AnimeTheme> = coroutineScope {
        val mal = identity.malId?.takeIf { it > 0 } ?: return@coroutineScope emptyList()
        val base = "https://api.jikan.moe/v4/anime/$mal"
        suspend fun request(path: String): Result<JsonElement> = try {
            val document = http.get("$base/$path".toHttpUrl())
            if ((document as? JsonObject)?.get("data") !is JsonObject) throw IOException("Invalid theme data response")
            Result.success(document)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
        val themes = async { request("themes") }
        val videos = async { request("videos") }
        val themeResult = themes.await()
        val videoResult = videos.await()
        val parsed = parse(
            themeResult.getOrNull() ?: JsonObject(emptyMap()),
            videoResult.getOrNull() ?: JsonObject(emptyMap()),
        )
        if (themeResult.isFailure || videoResult.isFailure) throw PartialThemeData(parsed)
        parsed
    }

    companion object {
        fun parse(themesDocument: JsonElement, videosDocument: JsonElement): List<AnimeTheme> {
            val data = themesDocument.field("data")
            val songs = listOf("openings" to ThemeKind.OPENING, "endings" to ThemeKind.ENDING).flatMap { (key, kind) ->
                data.items(key).mapIndexedNotNull { index, value ->
                    val raw = (value as? JsonPrimitive)?.contentOrNull ?: return@mapIndexedNotNull null
                    parseTheme(raw, kind, index + 1)
                }
            }
            val videos = videosDocument.field("data").items("music_videos")
            val attached = mutableMapOf<String, MutableList<ThemeVideo>>()
            for (item in videos) {
                val meta = item.field("meta")
                val metaTitle = meta.text("title").orEmpty()
                val itemTitle = item.text("title").orEmpty()
                val title = metaTitle.ifBlank { itemTitle }
                val video = item.field("video")
                val id = video.text("youtube_id")?.takeIf(ThemeVideoIds::valid)
                    ?: ThemeVideoIds.fromUrl(video.text("url")) ?: continue
                // A title fragment or a sequence number alone is not enough evidence.
                val exact = songs.filter { song ->
                    val named = extractQuotedTitle(title) ?: title
                    normalizeThemeTitle(named) == normalizeThemeTitle(song.title)
                }.singleOrNull() ?: continue
                attached.getOrPut(exact.key) { mutableListOf() }.add(
                    ThemeVideo(
                        id,
                        ThemeVideoIds.classify(itemTitle),
                        ThemeOrigin.JIKAN,
                        itemTitle,
                        meta.text("author").orEmpty(),
                    ),
                )
            }
            return songs.map { it.copy(videos = attached[it.key].orEmpty()) }
        }

        fun parseTheme(raw: String, kind: ThemeKind, fallbackNumber: Int): AnimeTheme? {
            val number = Regex("^\\s*(\\d+)\\s*:").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: fallbackNumber
            val withoutNumber = raw.replace(Regex("^\\s*\\d+\\s*:\\s*"), "").trim()
            val title = extractQuotedTitle(withoutNumber)
                ?: withoutNumber.substringBefore(" by ").substringBefore(" (eps").trim()
            if (title.isBlank()) return null
            val artist = withoutNumber.substringAfter(" by ", "").substringBefore(" (eps").trim()
            val episodes = Regex("\\(eps?\\.?\\s+([^)]+)\\)", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)
            return AnimeTheme(
                kind,
                number,
                title,
                listOf(artist).filter(String::isNotBlank),
                listOfNotNull(episodes?.let { ThemeVersion(episodes = it) }),
            )
        }

        private fun extractQuotedTitle(value: String): String? = Regex(
            "^[\"“](.+?)[\"”]",
        ).find(value)?.groupValues?.get(1)
    }
}

private fun JsonElement.field(name: String): JsonElement = (this as? JsonObject)?.get(name) ?: JsonNull
private fun JsonElement.items(name: String): List<JsonElement> = (field(name) as? JsonArray).orEmpty()
private fun JsonElement.primitive(name: String): JsonPrimitive? = field(name) as? JsonPrimitive
private fun JsonElement.text(name: String): String? = primitive(name)?.contentOrNull
private fun JsonElement.flag(name: String): Boolean = primitive(name)?.booleanOrNull == true

package it.lorelore.nyanime.themes

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.text.Normalizer
import java.util.Locale

@Serializable
enum class ThemeKind { OPENING, ENDING }

@Serializable
enum class ThemeVideoKind { SEQUENCE, FULL, UNKNOWN }

@Serializable
enum class ThemeOrigin { ANIMETHEMES, JIKAN, USER }

/** Catalogue identities only: never an extension URL or a local database ID. */
@Serializable
data class ThemeIdentity(val anilistId: Long? = null, val malId: Long? = null) {
    val valid get() = (anilistId ?: 0) > 0 || (malId ?: 0) > 0
    val key get() = "al:${anilistId ?: 0}:mal:${malId ?: 0}"
    val choiceKey get() = anilistId?.takeIf { it > 0 }?.let { "anilist:$it" } ?: "mal:${malId ?: 0}"
}

@Serializable
data class ThemeVideo(
    val id: String,
    val kind: ThemeVideoKind,
    val origin: ThemeOrigin,
    val title: String = "",
    val channel: String = "",
    val seconds: Int? = null,
    val spoiler: Boolean = false,
)

@Serializable
data class ThemeVersion(
    val number: Int? = null,
    val episodes: String? = null,
    val spoiler: Boolean = false,
)

@Serializable
data class AnimeTheme(
    val kind: ThemeKind,
    val number: Int,
    val title: String,
    val artists: List<String> = emptyList(),
    val versions: List<ThemeVersion> = emptyList(),
    val videos: List<ThemeVideo> = emptyList(),
) {
    val key get() = "${kind.name}:$number:${normalizeThemeTitle(title)}"
    val label get() = "${if (kind == ThemeKind.OPENING) "OP" else "ED"}$number"

    // The caller must also establish that catalogue and source episode numbering agree.
    fun includesEpisode(episode: Int): Boolean = versions.any { version ->
        val text = version.episodes ?: return@any false
        if (!Regex("\\d+(?:-\\d+)?(?:\\s*,\\s*\\d+(?:-\\d+)?)*").matches(text)) return@any false
        text.split(',').any { range ->
            val bounds = range.trim().split('-').mapNotNull(String::toIntOrNull)
            bounds.isNotEmpty() && episode in bounds.first()..bounds.last()
        }
    }
}

data class ThemeCatalogue(
    val songs: List<AnimeTheme> = emptyList(),
    val loading: Boolean = false,
    val partialFailure: Boolean = false,
    val cached: Boolean = false,
)

fun normalizeThemeTitle(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

object ThemeVideoIds {
    fun valid(value: String) = Regex("[A-Za-z0-9_-]{11}").matches(value)

    fun fromUrl(value: String?): String? {
        val url = value?.toHttpUrlOrNull() ?: return null
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        val id = when (url.host) {
            "youtu.be" -> url.pathSegments.singleOrNull()
            "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com" -> when {
                url.encodedPath == "/watch" -> url.queryParameter("v")
                url.pathSegments.size == 2 && url.pathSegments.first() in setOf("embed", "shorts", "live") ->
                    url.pathSegments.last()
                else -> null
            }
            else -> null
        }
        return id?.takeIf(::valid)
    }

    fun classify(title: String): ThemeVideoKind {
        val normalized = normalizeThemeTitle(title)
        val sequence = Regex("\\b(tv size|tv ver|creditless|non credit|noncredit|opening sequence|ending sequence)\\b")
            .containsMatchIn(normalized)
        val full = Regex("\\b(full song|full version|music video|official mv|mv)\\b").containsMatchIn(normalized)
        return when {
            sequence && !full -> ThemeVideoKind.SEQUENCE
            full && !sequence -> ThemeVideoKind.FULL
            else -> ThemeVideoKind.UNKNOWN
        }
    }
}

fun mergeThemes(songs: List<AnimeTheme>): List<AnimeTheme> = songs.groupBy { it.key }.values.map { group ->
    group.first().copy(
        artists = group.flatMap { it.artists }.distinct(),
        versions = group.flatMap { it.versions }.distinct(),
        videos = group.flatMap {
            it.videos
        }.filter { ThemeVideoIds.valid(it.id) }.groupBy { it.id }.map { (_, copies) ->
            val kinds = copies.map { it.kind }.filter { it != ThemeVideoKind.UNKNOWN }.distinct()
            copies.first().copy(
                kind = kinds.singleOrNull() ?: ThemeVideoKind.UNKNOWN,
                spoiler = copies.any { it.spoiler } || group.any { theme -> theme.versions.any { it.spoiler } },
            )
        },
    )
}.sortedWith(compareBy({ it.kind.ordinal }, { it.number }, { it.title }))

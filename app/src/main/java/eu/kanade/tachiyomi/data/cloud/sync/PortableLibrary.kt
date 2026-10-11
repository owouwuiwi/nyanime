package eu.kanade.tachiyomi.data.cloud.sync

import eu.kanade.tachiyomi.animesource.model.FetchType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import nyanime.telegram.sync.SyncRef
import nyanime.telegram.sync.syncJson
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.items.chapter.model.Chapter
import tachiyomi.domain.items.episode.model.Episode

/**
 * User state and enough display metadata to resume without a catalog search. No local row IDs,
 * fetched stream links, extension objects, local file paths or opaque memo blobs cross this boundary.
 */
@Serializable
internal data class PortableTitle(
    val name: String,
    val favorite: Boolean = false,
    val added: Long,
    val viewer: Long,
    val flags: Long,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val status: Long = 0,
    val cover: String? = null,
    val backdrop: String? = null,
    val seasonFlags: Long = 0,
    val seasonNumber: Double = 0.0,
    val seasonOrder: Long = 0,
    val fetchType: String = "Episodes",
    val parent: SyncRef? = null,
) {
    fun validate() {
        require(name.isNotBlank() && name.length <= 4096)
        require(genres.size <= 256 && genres.all { it.length <= 512 })
        require(description.orEmpty().length <= 512 * 1024)
        require(added >= 0 && seasonNumber.isFinite())
        parent?.validate()
    }

    fun fields(): JsonObject = syncJson.encodeToJsonElement(this).jsonObject

    fun anime(ref: SyncRef, previous: Anime = Anime.create(), parentId: Long? = null): Anime {
        validate()
        require(ref.kind == "anime-title" && ref.source != null && ref.item.isEmpty())
        return previous.copy(
            source = requireNotNull(ref.source), url = ref.title, title = name, favorite = favorite, dateAdded = added,
            viewerFlags = viewer, episodeFlags = flags, author = author, artist = artist,
            description = description, genre = genres, status = status, thumbnailUrl = cover,
            backgroundUrl = backdrop, seasonFlags = seasonFlags, seasonNumber = seasonNumber,
            seasonSourceOrder = seasonOrder, parentId = parentId,
            fetchType = FetchType.entries.firstOrNull { it.name == fetchType } ?: previous.fetchType,
        )
    }

    fun manga(ref: SyncRef, previous: Manga = Manga.create()): Manga {
        validate()
        require(ref.kind == "manga-title" && ref.source != null && ref.item.isEmpty())
        return previous.copy(
            source = requireNotNull(ref.source), url = ref.title, title = name, favorite = favorite, dateAdded = added,
            viewerFlags = viewer, chapterFlags = flags, author = author, artist = artist,
            description = description, genre = genres, status = status, thumbnailUrl = cover,
        )
    }

    companion object {
        fun from(anime: Anime, parent: SyncRef? = null) = PortableTitle(
            anime.title, anime.favorite, anime.dateAdded, anime.viewerFlags, anime.episodeFlags,
            anime.author, anime.artist, anime.description, anime.genre.orEmpty(), anime.status,
            anime.thumbnailUrl, anime.backgroundUrl, anime.seasonFlags, anime.seasonNumber,
            anime.seasonSourceOrder, anime.fetchType.name, parent,
        )

        fun from(manga: Manga) = PortableTitle(
            manga.title, manga.favorite, manga.dateAdded, manga.viewerFlags, manga.chapterFlags,
            manga.author, manga.artist, manga.description, manga.genre.orEmpty(), manga.status, manga.thumbnailUrl,
        )
    }
}

@Serializable
internal data class PortableItem(
    val name: String,
    val number: Double,
    val order: Long,
    val completed: Boolean = false,
    val bookmark: Boolean = false,
    val scanlator: String? = null,
    val dateUpload: Long = 0,
    val filler: Boolean = false,
) {
    fun fields(): JsonObject = syncJson.encodeToJsonElement(this).jsonObject
    fun validate() {
        require(name.isNotBlank() && name.length <= 4096 && number.isFinite())
        require(scanlator.orEmpty().length <= 4096 && dateUpload >= 0)
    }

    fun episode(ref: SyncRef, animeId: Long, previous: Episode = Episode.create()): Episode {
        validate()
        require(ref.kind == "anime-item" && ref.item.isNotEmpty())
        return previous.copy(
            animeId = animeId, url = ref.item, name = name, episodeNumber = number,
            sourceOrder = order, seen = completed, bookmark = bookmark, scanlator = scanlator,
            dateUpload = dateUpload, fillermark = filler,
        )
    }

    fun chapter(ref: SyncRef, mangaId: Long, previous: Chapter = Chapter.create()): Chapter {
        validate()
        require(ref.kind == "manga-item" && ref.item.isNotEmpty())
        return previous.copy(
            mangaId = mangaId, url = ref.item, name = name, chapterNumber = number,
            sourceOrder = order, read = completed, bookmark = bookmark, scanlator = scanlator,
            dateUpload = dateUpload,
        )
    }

    companion object {
        fun from(episode: Episode) = PortableItem(
            episode.name,
            episode.episodeNumber,
            episode.sourceOrder,
            episode.seen,
            episode.bookmark,
            episode.scanlator,
            episode.dateUpload.coerceAtLeast(0),
            episode.fillermark,
        )

        fun from(chapter: Chapter) = PortableItem(
            chapter.name,
            chapter.chapterNumber,
            chapter.sourceOrder,
            chapter.read,
            chapter.bookmark,
            chapter.scanlator,
            chapter.dateUpload.coerceAtLeast(0),
        )
    }
}

@Serializable
internal data class PortablePosition(
    val position: Long,
    val duration: Long = 0,
    val device: String,
    val deviceName: String,
    val session: String,
    val at: Long,
) {
    fun validate() {
        require(position >= 0 && duration >= 0 && at >= 0)
        require(deviceName.length in 1..80)
        require(java.util.UUID.fromString(device).toString() == device)
        require(java.util.UUID.fromString(session).toString() == session)
    }
}

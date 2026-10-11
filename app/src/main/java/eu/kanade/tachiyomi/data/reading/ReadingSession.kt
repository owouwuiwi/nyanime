package eu.kanade.tachiyomi.data.reading

import eu.kanade.tachiyomi.data.watch.watchHash
import eu.kanade.tachiyomi.data.watch.watchHex
import eu.kanade.tachiyomi.data.watch.watchJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** A chapter choice is independent of page images, page counts and personal progress. */
@Serializable
data class ReadingChapter(
    val source: Long,
    val manga: String,
    val chapter: String,
    val title: String,
    val chapterName: String,
    val catalogIds: Map<String, Long> = emptyMap(),
) {
    val key: String get() = watchHash("$source\n$manga\n$chapter".toByteArray()).watchHex()
    fun valid(): Boolean = source != 0L &&
        manga.length in 1..1024 &&
        chapter.length in 1..1024 &&
        title.isNotBlank() &&
        title.length <= 240 &&
        chapterName.isNotBlank() &&
        chapterName.length <= 240 &&
        catalogIds.size <= 3 &&
        catalogIds.all { it.key in setOf("anilist", "myanimelist", "mangaupdates") && it.value > 0 } &&
        watchJson.encodeToString(this).toByteArray().size <= 2300

    fun sameCatalog(other: ReadingChapter): Boolean {
        val common = catalogIds.keys.intersect(other.catalogIds.keys)
        return common.isNotEmpty() && common.all { catalogIds[it] == other.catalogIds[it] }
    }
}

fun ReadingPosition.chapterReference() = ReadingChapter(source, manga, chapter, title, chapterName)

@Serializable
data class ReadingSelection(val revision: Long, val chapter: ReadingChapter) {
    fun valid(): Boolean = revision > 0 && chapter.valid()
}

@Serializable
enum class ReadingSessionKind { Snapshot, Choose, Propose, Receipt }

@Serializable
enum class ReadingOpening { Received, Opening, Ready, SourceMissing, Failed, Personal }

/** Optional sibling of the v2 annotation envelope. Old clients ignore this whole field. */
@Serializable
data class ReadingSessionEnvelope(
    val version: Int = 1,
    val kind: ReadingSessionKind,
    val revision: Long = 0,
    val selection: ReadingSelection? = null,
    val chapter: ReadingChapter? = null,
    val permissions: Set<String> = emptySet(),
    val request: Long = 0,
    val expiresAt: Long = 0,
    val acknowledgement: Long = 0,
    val rejected: Boolean = false,
    val opening: ReadingOpening? = null,
    val openings: Map<String, ReadingOpening> = emptyMap(),
) {
    fun valid(): Boolean = version == 1 &&
        revision >= 0 &&
        request >= 0 &&
        expiresAt >= 0 &&
        acknowledgement >= 0 &&
        permissions.size <= 7 &&
        permissions.all(::isReadingKey) &&
        openings.size <= 8 &&
        openings.keys.all(::isReadingKey) &&
        (selection == null || selection.valid() && selection.revision <= revision) &&
        (chapter == null || chapter.valid()) &&
        watchJson.encodeToString(this).toByteArray().size <= 4000 &&
        when (kind) {
            ReadingSessionKind.Snapshot -> chapter == null && request == 0L && opening == null
            ReadingSessionKind.Choose, ReadingSessionKind.Propose ->
                chapter != null &&
                    selection == null &&
                    request > 0 &&
                    expiresAt > 0 &&
                    permissions.isEmpty() &&
                    opening == null &&
                    openings.isEmpty() &&
                    acknowledgement == 0L
            ReadingSessionKind.Receipt ->
                opening != null &&
                    revision > 0 &&
                    chapter == null &&
                    selection == null &&
                    permissions.isEmpty() &&
                    openings.isEmpty() &&
                    request == 0L
        }
}

data class ReadingSessionContext(
    val active: Boolean = false,
    val roomId: String = "",
    val localId: String = "",
    val ownerId: String = "",
    val host: Boolean = false,
    val members: Map<String, Int> = emptyMap(),
    val connected: Boolean = false,
    val requestFloor: Long = 0,
)

data class ReadingProposal(val participant: String, val request: Long, val chapter: ReadingChapter)

data class ReadingSessionState(
    val selection: ReadingSelection? = null,
    val permissions: Set<String> = emptySet(),
    val proposals: List<ReadingProposal> = emptyList(),
    val openings: Map<String, ReadingOpening> = emptyMap(),
    val supported: Boolean = false,
    val legacyMembers: Set<String> = emptySet(),
    val personal: Boolean = false,
    val pending: Boolean = false,
    val commandFailed: Boolean = false,
)

/** Tokens protect *all* asynchronous room openings, including a switch of medium. */
class RoomNavigationGeneration {
    private var room = ""
    private var content = ""
    private var generation = 0L
    fun select(roomId: String, key: String): Long {
        if (room != roomId || content != key) {
            room = roomId
            content = key
            generation++
        }
        return generation
    }
    fun cancel() {
        generation++
    }
    fun current(token: Long): Boolean = token == generation
}

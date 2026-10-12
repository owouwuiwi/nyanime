package eu.kanade.tachiyomi.data.reading

import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.watch.RoomText
import eu.kanade.tachiyomi.data.watch.watchHash
import eu.kanade.tachiyomi.data.watch.watchHex
import eu.kanade.tachiyomi.data.watch.watchJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** Catalog references only. Image URLs, authentication and database IDs never leave the device. */
@Serializable
data class ReadingPosition(
    val source: Long,
    val manga: String,
    val chapter: String,
    val title: String,
    val chapterName: String,
    val page: Int,
    val pages: Int,
    val fingerprint: String = "",
) {
    @kotlinx.serialization.Transient
    val chapterKey: String = watchHash("$source\n$manga\n$chapter".toByteArray()).watchHex()

    @kotlinx.serialization.Transient
    val pageKey: String = "$chapterKey:$page:$pages"
    fun valid(): Boolean = source != 0L &&
        manga.length in 1..1024 &&
        chapter.length in 1..1024 &&
        title.length in 1..240 &&
        chapterName.length in 1..240 &&
        pages in 1..5000 &&
        page in 0 until pages &&
        (fingerprint.isEmpty() || fingerprint.matches(Regex("[0-9a-f]{64}"))) &&
        watchJson.encodeToString(this).toByteArray().size <= 2400
}

@Serializable
data class ReadingPeer(val name: String, val position: ReadingPosition? = null, val reading: Boolean = false) {
    fun valid(): Boolean = name.length in 1..32 &&
        (position == null || position.valid()) &&
        watchJson.encodeToString(this).toByteArray().size <= 2500
}

/** Quantized page coordinates; fixed bounds keep network, memory and drawing work predictable. */
@Serializable
data class ReadingStroke(
    val id: String,
    val author: String,
    val color: Int,
    val width: Int,
    val points: List<Int>,
) {
    fun valid(): Boolean = id.matches(Regex("[0-9a-f]{32}")) &&
        isReadingKey(author) &&
        color in 0..4 &&
        width in 1..3 &&
        points.size in 4..192 &&
        points.size % 2 == 0 &&
        points.all { it in 0..10000 }
}

@Serializable
enum class ReadingKind { Presence, Roster, Ink, Board, Leave, Closed, Crdt, Sync }

@Serializable
data class ReadingEnvelope(
    val version: Int = 1,
    val kind: ReadingKind,
    val peer: ReadingPeer? = null,
    val members: Map<String, ReadingPeer> = emptyMap(),
    val page: ReadingPosition? = null,
    val operation: Long = 0,
    val stroke: ReadingStroke? = null,
    val erase: String = "",
    val clear: Boolean = false,
    val revision: Long = 0,
    val strokes: List<ReadingStroke> = emptyList(),
    val acknowledgement: Long = 0,
    val error: String = "",
    val errorCode: String = "",
    val edits: List<ReadingEdit> = emptyList(),
    val digest: String = "",
) {
    fun valid(): Boolean = version in 1..2 &&
        (peer == null || peer.valid()) &&
        members.size <= 8 &&
        members.all { isReadingKey(it.key) && it.value.valid() } &&
        (page == null || page.valid()) &&
        operation >= 0 &&
        revision >= 0 &&
        acknowledgement >= 0 &&
        (stroke == null || stroke.valid()) &&
        (erase.isEmpty() || erase.matches(Regex("[0-9a-f]{32}"))) &&
        strokes.size <= 12 &&
        strokes.all { it.valid() } &&
        strokes.map { it.id }.distinct().size == strokes.size &&
        edits.size <= 4 &&
        edits.all { it.valid() && it.page.pageKey == page?.pageKey } &&
        (digest.isEmpty() || digest.matches(Regex("[0-9a-f]{64}"))) &&
        error.length <= 240 &&
        errorCode.length <= 32 &&
        watchJson.encodeToString(this).toByteArray().size <= 22000 &&
        when (kind) {
            ReadingKind.Presence -> peer != null
            ReadingKind.Ink ->
                page != null &&
                    operation > 0 &&
                    listOf(stroke != null, erase.isNotEmpty(), clear).count { it } == 1
            ReadingKind.Board -> page != null
            ReadingKind.Crdt -> version == 2 && page != null && edits.isNotEmpty()
            ReadingKind.Sync -> version == 2 && page != null && digest.isNotEmpty()
            else -> true
        }
}

internal fun isReadingKey(value: String): Boolean = value.matches(Regex("[0-9a-f]{64}"))

data class ReadingBoard(
    val revision: Long = 0,
    val strokes: List<ReadingStroke> = emptyList(),
    val notes: List<ReadingNote> = emptyList(),
)

data class ReadingRoomState(
    val active: Boolean = false,
    val host: Boolean = false,
    val localId: String = "",
    val invite: String = "",
    val relayCount: Int = 0,
    val connected: Boolean = false,
    val supported: Boolean = true,
    val crdtEnabled: Boolean = false,
    val members: Map<String, ReadingPeer> = emptyMap(),
    val boards: Map<String, ReadingBoard> = emptyMap(),
    val pending: List<ReadingEnvelope> = emptyList(),
    val notice: String = "",
) {
    val others: Map<String, ReadingPeer> get() = members.filterKeys { it != localId }
    fun status(text: RoomText): String = when {
        !active -> notice
        relayCount == 0 -> text(R.string.room_reading_reconnecting)
        !connected && members.isEmpty() -> text(R.string.room_reading_waiting)
        !supported -> text(R.string.room_reading_update_required)
        !connected -> text(R.string.room_reading_waiting)
        pending.isNotEmpty() -> text(R.string.room_pending_sketches, pending.size)
        others.isEmpty() -> text(R.string.room_invite_reader)
        else -> text(R.string.room_reader_count, members.size)
    }

    fun strokes(page: ReadingPosition): List<ReadingStroke> {
        if (pending.none { it.page?.pageKey == page.pageKey }) return boards[page.pageKey]?.strokes.orEmpty()
        val result = boards[page.pageKey]?.strokes.orEmpty().toMutableList()
        pending.filter { it.page?.pageKey == page.pageKey }.forEach { operation ->
            if (operation.clear) result.removeAll { host || it.author == localId }
            if (operation.erase.isNotEmpty()) result.removeAll { it.id == operation.erase }
            operation.stroke?.let { stroke -> if (result.none { it.id == stroke.id }) result.add(stroke) }
        }
        return result
    }

    fun notes(page: ReadingPosition): List<ReadingNote> = boards[page.pageKey]?.notes.orEmpty()
}

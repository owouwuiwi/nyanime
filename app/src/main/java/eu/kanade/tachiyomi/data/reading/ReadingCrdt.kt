package eu.kanade.tachiyomi.data.reading

import eu.kanade.tachiyomi.data.watch.watchHash
import eu.kanade.tachiyomi.data.watch.watchHex
import kotlinx.serialization.Serializable

/** Immutable operations: union is associative, commutative and idempotent. */
@Serializable
enum class ReadingEditKind { Stroke, Note, NoteText, Visibility }

@Serializable
data class ReadingNote(
    val id: String,
    val author: String,
    val x: Int,
    val y: Int,
    val text: String,
) {
    fun valid(): Boolean = isReadingId(id) &&
        isReadingKey(author) &&
        x in 0..10000 &&
        y in 0..10000 &&
        text.isNotBlank() &&
        text.length <= 280
}

@Serializable
data class ReadingEdit(
    val id: String,
    val author: String,
    val page: ReadingPosition,
    val clock: Long,
    val kind: ReadingEditKind,
    val stroke: ReadingStroke? = null,
    val note: ReadingNote? = null,
    val target: String = "",
    val text: String = "",
    val visible: Boolean = true,
) {
    fun valid(): Boolean = isReadingId(id) &&
        isReadingKey(author) &&
        page.valid() &&
        clock in 1..1_000_000_000L &&
        when (kind) {
            ReadingEditKind.Stroke -> stroke?.valid() == true && stroke.id == id && stroke.author == author
            ReadingEditKind.Note -> note?.valid() == true && note.id == id && note.author == author
            ReadingEditKind.NoteText -> isReadingId(target) && text.isNotBlank() && text.length <= 280
            ReadingEditKind.Visibility -> isReadingId(target)
        }
}

internal fun isReadingId(value: String): Boolean = value.matches(Regex("[0-9a-f]{32}"))

/** Page-scoped operation set. Hidden creations remain until the room closes, preventing resurrection. */
class ReadingCrdt(var hostId: String = "") {
    private val edits = linkedMapOf<String, ReadingEdit>()
    private val byPage = mutableMapOf<String, MutableSet<String>>()
    private var historyCache: List<ReadingEdit>? = null
    var clock: Long = 0
        private set

    fun apply(edit: ReadingEdit): Boolean {
        if (!edit.valid() || edit.id in edits || edits.size >= 8192) return false
        if (edit.page.pageKey !in byPage && byPage.size >= 64) return false
        val pageEdits = byPage.getOrPut(edit.page.pageKey) { linkedSetOf() }
        if (pageEdits.size >= 256) return false
        edits[edit.id] = edit
        historyCache = null
        pageEdits += edit.id
        clock = maxOf(clock, edit.clock)
        return true
    }

    fun nextClock(): Long = ++clock

    fun all(): List<ReadingEdit> = edits.values.toList()

    fun page(page: ReadingPosition): List<ReadingEdit> = byPage[page.pageKey].orEmpty().mapNotNull(edits::get)

    fun digest(page: ReadingPosition): String = watchHash(
        byPage[page.pageKey].orEmpty().sorted().joinToString("").toByteArray(),
    ).watchHex()

    fun creator(page: ReadingPosition, target: String): ReadingEdit? = page(page).firstOrNull {
        it.id == target && it.kind in setOf(ReadingEditKind.Stroke, ReadingEditKind.Note)
    }

    fun strokes(page: ReadingPosition): List<ReadingStroke> {
        val operations = page(page)
        val visibility = visibility(operations)
        return operations.mapNotNull { edit ->
            edit.stroke?.takeIf { edit.kind == ReadingEditKind.Stroke && visibility[edit.id] != false }
        }.sortedBy { it.id }
    }

    fun notes(page: ReadingPosition): List<ReadingNote> {
        val operations = page(page)
        val visibility = visibility(operations)
        val creators = operations.filter { it.kind == ReadingEditKind.Note }.associateBy { it.id }
        val changes = operations.filter {
            it.kind == ReadingEditKind.NoteText &&
                creators[it.target]?.author == it.author
        }
            .groupBy { it.target }
            .mapValues { (_, items) -> items.maxWithOrNull(editOrder)?.text }
        return operations.mapNotNull { edit ->
            edit.note?.takeIf { edit.kind == ReadingEditKind.Note && visibility[edit.id] != false }
                ?.let { note -> note.copy(text = changes[note.id] ?: note.text) }
        }.sortedBy { it.id }
    }

    fun history(): List<ReadingEdit> = historyCache ?: edits.values.sortedWith(editOrder.reversed()).also {
        historyCache = it
    }

    fun history(author: String): List<ReadingEdit> = history().filter { it.author == author }

    private fun visibility(operations: List<ReadingEdit>): Map<String, Boolean> {
        val creators = operations.filter { it.kind in setOf(ReadingEditKind.Stroke, ReadingEditKind.Note) }
            .associateBy { it.id }
        return operations
            .filter {
                it.kind == ReadingEditKind.Visibility &&
                    creators[it.target]?.let { creator -> it.author == creator.author || it.author == hostId } == true
            }
            .groupBy { it.target }
            .mapValues { (_, items) -> items.maxWithOrNull(editOrder)?.visible ?: true }
    }

    companion object {
        private val editOrder = compareBy<ReadingEdit>({ it.clock }, { it.author }, { it.id })
    }
}

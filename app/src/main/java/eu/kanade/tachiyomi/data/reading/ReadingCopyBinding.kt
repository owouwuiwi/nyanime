package eu.kanade.tachiyomi.data.reading

import kotlinx.serialization.Serializable

/** Explicit, local-only chapter association. IDs and titles are never interpreted as page equivalence. */
@Serializable
data class ReadingCopyBinding(
    val room: String,
    val shared: ReadingChapter,
    val local: ReadingChapter,
    val mangaId: Long,
    val chapterId: Long,
) {
    fun valid(): Boolean = room.matches(Regex("[0-9a-f]{64}")) &&
        shared.valid() &&
        local.valid() &&
        mangaId > 0 &&
        chapterId > 0
}

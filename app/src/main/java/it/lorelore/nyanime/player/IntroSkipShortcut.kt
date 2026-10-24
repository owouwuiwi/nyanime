package it.lorelore.nyanime.player

import eu.kanade.tachiyomi.animesource.model.ChapterType
import eu.kanade.tachiyomi.ui.player.controls.components.IndexedSegment
import tachiyomi.domain.custombuttons.model.CustomButton

/** Visibility of the built-in intro shortcut, without changing user-authored button scripts. */
internal object IntroSkipShortcut {
    // Existing installations store this button in the editable custom-button table.
    // Match its action, never the displayed title or just its database ID.
    private val defaultAction = """
        local intro_length = mp.get_property_number("user-data/current-anime/intro-length")
        aniyomi.right_seek_by(intro_length)
    """.trimIndent()

    fun isDefault(button: CustomButton): Boolean =
        button.id == 1L && button.content.replace("\r\n", "\n").trim() == defaultAction

    data class Skipped(val episodeId: Long, val start: Float, val target: Float, val reached: Boolean = false) {
        fun preparing(episodeId: Long?): Skipped? =
            if (episodeId != null && episodeId != this.episodeId) null else copy(reached = false)

        fun observe(episodeId: Long?, position: Float): Skipped? = when {
            episodeId != this.episodeId -> null
            !position.isFinite() -> this
            !reached -> if (position >= target - 1f) copy(reached = true) else this
            position <= start -> null
            else -> this
        }
    }

    fun skipped(episodeId: Long?, position: Float, length: Int, duration: Float): Skipped? {
        if (episodeId == null || !position.isFinite() || position < 0f || length <= 0) return null
        val target = (position + length).let {
            if (duration.isFinite() && duration > 0f) it.coerceAtMost(duration) else it
        }
        return target.takeIf { it.isFinite() && it > position }?.let { Skipped(episodeId, position, it) }
    }

    fun visible(
        button: CustomButton?,
        episodeId: Long?,
        position: Float,
        chapters: List<IndexedSegment>,
        skipped: Skipped?,
    ): CustomButton? {
        if (button == null || !isDefault(button)) return button
        if (skipped != null && skipped.episodeId == episodeId) return null
        if (!position.isFinite()) return button
        val opening = chapters.filter {
            it.start.isFinite() &&
                it.start >= 0f &&
                it.start <= position &&
                (it.chapterType == ChapterType.Opening || it.chapterType == ChapterType.MixedOp)
        }.maxByOrNull { it.start } ?: return button
        val end = chapters.asSequence().map { it.start }
            .filter { it.isFinite() && it > opening.start }.minOrNull() ?: return button
        return button.takeIf { position < end }
    }
}

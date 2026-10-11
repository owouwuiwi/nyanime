package it.lorelore.nyanime.presentation.entries

import eu.kanade.presentation.entries.details.DetailPart

/** Ordering hints never become identity. Unknown numbers keep the order declared by the source. */
internal object EntryResumePreview {
    fun items(parts: List<DetailPart>, currentId: Long?, limit: Int = 3): List<DetailPart> {
        if (limit <= 0) return emptyList()
        val current = parts.firstOrNull { it.id == currentId }
            ?: return parts.filterNot { it.completed }.ifEmpty { parts }.take(limit)
        val number = current.sequence?.takeIf { it.isFinite() && it >= 0 }
        val candidates = parts.filter { it.id != current.id && !it.completed }
        val next = if (number == null) {
            candidates
        } else {
            candidates.filter { it.sequence?.let { sequence -> sequence.isFinite() && sequence > number } == true }
                .sortedBy { it.sequence } +
                candidates.filter { it.sequence == null || !it.sequence.isFinite() || it.sequence < 0 }
        }
        return listOf(current) + next.take(limit - 1)
    }
}

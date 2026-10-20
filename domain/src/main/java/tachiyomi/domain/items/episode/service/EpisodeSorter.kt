package tachiyomi.domain.items.episode.service

import tachiyomi.core.common.util.lang.compareToWithCollator
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.items.episode.model.Episode
import tachiyomi.domain.items.episode.model.EpisodeGroup
import tachiyomi.domain.items.episode.model.group
import java.util.IdentityHashMap

fun getEpisodeSort(anime: Anime, sortDescending: Boolean = anime.sortDescending()): (
    Episode,
    Episode,
) -> Int {
    return when (anime.sorting) {
        Anime.EPISODE_SORTING_SOURCE -> when (sortDescending) {
            true -> { e1, e2 -> e1.sourceOrder.compareTo(e2.sourceOrder) }
            false -> { e1, e2 -> e2.sourceOrder.compareTo(e1.sourceOrder) }
        }
        Anime.EPISODE_SORTING_NUMBER -> {
            val order = EpisodeNumberOrder()
            if (sortDescending) { e1, e2 -> order.compare(e2, e1) } else order::compare
        }
        Anime.EPISODE_SORTING_UPLOAD_DATE -> when (sortDescending) {
            true -> { e1, e2 -> e2.dateUpload.compareTo(e1.dateUpload) }
            false -> { e1, e2 -> e1.dateUpload.compareTo(e2.dateUpload) }
        }
        Anime.EPISODE_SORTING_ALPHABET -> when (sortDescending) {
            true -> { e1, e2 -> e2.name.compareToWithCollator(e1.name) }
            false -> { e1, e2 -> e1.name.compareToWithCollator(e2.name) }
        }
        else -> throw NotImplementedError("Invalid episode sorting method: ${anime.sorting}")
    }
}

private class EpisodeNumberOrder {
    // Local to one sorting operation. Decode metadata once per immutable episode, not per comparison.
    private val groups = IdentityHashMap<Episode, EpisodeGroup?>()
    private fun group(episode: Episode): EpisodeGroup? {
        if (!groups.containsKey(episode)) groups[episode] = episode.group
        return groups[episode]
    }

    fun compare(first: Episode, second: Episode): Int {
        val a = group(first)
        val b = group(second)
        // A total order is required even for partially grouped snapshots.
        val groupOrder = (a?.order ?: Int.MAX_VALUE).compareTo(b?.order ?: Int.MAX_VALUE)
        if (groupOrder != 0) return groupOrder
        val groupId = (a?.id.orEmpty()).compareTo(b?.id.orEmpty())
        if (groupId != 0) return groupId
        return first.episodeNumber.compareTo(second.episodeNumber)
    }
}

package eu.kanade.presentation.components.releases

import tachiyomi.data.discovery.mergeHomeCards
import tachiyomi.domain.discovery.homePresentation
import tachiyomi.domain.entries.anime.model.Anime

/** Reuses Home's verified identity and conflicting-season safeguards. Local source choices remain concrete. */
internal object ReleaseAgendaMerge {
    fun merge(
        items: List<ReleaseAgendaItem>,
        works: List<Anime>,
        present: Map<Long, Set<Double>> = emptyMap(),
        watched: Map<Long, Set<Double>> = emptyMap(),
        now: Long = System.currentTimeMillis(),
    ): List<ReleaseAgendaItem> {
        val groups = mergeHomeCards(works.sortedBy { it.id }, mergeSourceVariants = true)
        val members = groups.associate { work ->
            work.id to (work.homePresentation?.choices?.map { it.animeId } ?: listOf(work.id))
        }
        val groupOf = members.flatMap { (group, ids) -> ids.map { it to group } }.toMap()
        val byId = works.associateBy { it.id }
        val grouped = items.groupBy { item ->
            val number = item.number?.takeIf { it.isFinite() && it > 0 }
            if (number == null) item.key else "${groupOf[item.entryId] ?: item.entryId}:$number"
        }
        return grouped.values.mapNotNull { options ->
            val first = options.first()
            val group = groupOf[first.entryId] ?: first.entryId
            val ids = members[group] ?: listOf(first.entryId)
            val number = first.number
            val futureBroadcast = options.any { it.itemId == null && it.at > now }
            if (!futureBroadcast &&
                number != null &&
                ids.any { number in watched[it].orEmpty() }
            ) {
                return@mapNotNull null
            }
            val available = options.filter { it.itemId != null }
            if (!futureBroadcast &&
                available.isEmpty() &&
                number != null &&
                ids.any { number in present[it].orEmpty() }
            ) {
                return@mapNotNull null
            }
            val choices = (available.ifEmpty { options }).sortedWith(
                compareBy<ReleaseAgendaItem> { it.at }.thenBy { it.entryId },
            ).distinctBy { it.entryId }
            val selected = choices.first()
            selected.copy(
                key = if (number == null) selected.key else "anime-release-$group-$number",
                choices = choices.takeIf { it.size > 1 }.orEmpty(),
                relatedEntryIds = ids.toSet(),
                agendaKeys = if (number != null && number.isFinite() && number > 0) {
                    ids.mapNotNull { byId[it] }.mapTo(mutableSetOf()) {
                        ReleaseAgendaActions.key(selected.medium, it.source, it.url, number)
                    }
                } else {
                    options.flatMap { it.agendaKeys }.toSet()
                },
            )
        }
    }

    /** Conflicting evidence cannot be silently overwritten to enable a merge. */
    fun verifiedIds(vararg evidence: Map<String, Long>): Map<String, Long>? {
        val ids = evidence.flatMap { it.entries }.filter { it.value > 0 }.groupBy { it.key }
        if (ids.values.any { entries -> entries.map { it.value }.distinct().size > 1 }) return null
        return ids.mapValues { it.value.first().value }
    }
}

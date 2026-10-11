package eu.kanade.presentation.components.releases

import eu.kanade.tachiyomi.data.releases.ReleaseMedium
import java.util.Base64

/** A dismissal follows one release through date changes and local database-ID remapping. */
internal object ReleaseAgendaActions {
    fun key(medium: ReleaseMedium, source: Long, titleUrl: String, number: Double?, itemUrl: String = ""): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val title = encoder.encodeToString(titleUrl.toByteArray(Charsets.UTF_8))
        val release = number?.takeIf { it.isFinite() && it > 0 }?.let { "number-$it" }
            ?: "item-${encoder.encodeToString(itemUrl.toByteArray(Charsets.UTF_8))}"
        return "v1|$medium|$source|$title|$release"
    }

    fun entries(item: ReleaseAgendaItem): Set<Long> =
        item.relatedEntryIds + item.entryId + item.choices.map { it.entryId }

    fun dismissalKeys(item: ReleaseAgendaItem): Set<String> =
        item.agendaKeys + item.choices.flatMap { it.agendaKeys }

    fun dismissed(item: ReleaseAgendaItem, keys: Set<String>): Boolean =
        (
            dismissalKeys(item) +
                item.plannedAgendaKeys +
                item.choices.flatMap {
                    it.plannedAgendaKeys
                }
            ).any { it in keys }

    fun playableOptions(item: ReleaseAgendaItem): List<ReleaseAgendaItem> =
        item.choices.ifEmpty { listOf(item) }.filter { it.itemId != null }
}

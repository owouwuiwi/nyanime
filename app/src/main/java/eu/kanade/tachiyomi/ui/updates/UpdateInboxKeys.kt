package eu.kanade.tachiyomi.ui.updates

import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.updates.anime.model.AnimeUpdatesWithRelations
import tachiyomi.domain.updates.manga.model.MangaUpdatesWithRelations
import java.time.ZonedDateTime

// Metadata-based keys remain stable when a backup remaps local database IDs.
fun AnimeUpdatesWithRelations.inboxKey() =
    "$dateFetch|anime|$sourceId|$animeTitle|$episodeName"

fun MangaUpdatesWithRelations.inboxKey() =
    "$dateFetch|manga|$sourceId|$mangaTitle|$chapterName"

fun dismissLibraryUpdate(preference: Preference<Set<String>>, key: String) {
    dismissLibraryUpdates(preference, setOf(key))
}

fun dismissLibraryUpdates(preference: Preference<Set<String>>, keys: Set<String>) {
    val cutoff = ZonedDateTime.now().minusMonths(6).toInstant().toEpochMilli()
    preference.set(
        preference.get().filterTo(mutableSetOf()) {
            (it.substringBefore('|').toLongOrNull() ?: 0L) >= cutoff
        } +
            keys,
    )
}

fun hasNewLibraryUpdateNotice(keys: Collection<String>, lastSeenAt: Long) = keys.any {
    (it.substringBefore('|').toLongOrNull() ?: 0L) > lastSeenAt
}

fun markLibraryUpdateNoticesSeen(preference: Preference<Long>, keys: Collection<String>) {
    val latest = keys.maxOfOrNull { it.substringBefore('|').toLongOrNull() ?: 0L } ?: 0L
    preference.set(maxOf(preference.get(), System.currentTimeMillis(), latest))
}

package tachiyomi.domain.history.anime.model

/** Null means the complete history; an unavailable/empty Home must yield no entries. */
fun List<AnimeHistoryWithRelations>.forSources(sourceIds: Set<Long>?): List<AnimeHistoryWithRelations> =
    if (sourceIds == null) this else filter { it.coverData.sourceId in sourceIds }

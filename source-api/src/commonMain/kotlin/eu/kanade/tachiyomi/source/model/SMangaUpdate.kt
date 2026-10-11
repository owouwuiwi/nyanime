package eu.kanade.tachiyomi.source.model

/** Combined manga details and chapters returned by newer extensions. */
class SMangaUpdate(
    val manga: SManga,
    val chapters: List<SChapter>,
)

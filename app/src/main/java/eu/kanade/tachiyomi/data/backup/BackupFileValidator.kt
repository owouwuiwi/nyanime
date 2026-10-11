package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.track.TrackerManager
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.source.manga.service.MangaSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class BackupFileValidator(
    private val context: Context,
    private val animeSourceManager: AnimeSourceManager = Injekt.get(),
    private val mangaSourceManager: MangaSourceManager = Injekt.get(),
    private val trackerManager: TrackerManager = Injekt.get(),
) {

    /**
     * Checks for critical backup file data.
     *
     * @return List of missing sources or missing trackers.
     */
    fun validate(uri: Uri): Results {
        val backup = try {
            BackupDecoder(context).decode(uri)
        } catch (e: Exception) {
            throw IllegalStateException(e)
        }

        val sources = backup.backupSources.associate { it.sourceId to it.name }
        val animesources = backup.backupAnimeSources.associate { it.sourceId to it.name }
        val missingSources = sources
            .filter { mangaSourceManager.get(it.key) == null }
            .values.map {
                val id = it.toLongOrNull()
                if (id == null) {
                    it
                } else {
                    mangaSourceManager.getOrStub(id).toString()
                }
            }
            .distinct()
            .sorted() +
            animesources
                .filter { animeSourceManager.get(it.key) == null }
                .values.map {
                    val id = it.toLongOrNull()
                    if (id == null) {
                        it
                    } else {
                        animeSourceManager.getOrStub(id).toString()
                    }
                }
                .distinct()
                .sorted()

        val animeTrackers = backup.backupAnime
            .flatMap { it.tracking }
            .map { it.syncId }
        val mangaTrackers = backup.backupManga
            .flatMap { it.tracking }
            .map { it.syncId }
        val trackers = (animeTrackers + mangaTrackers).distinct()
        val missingTrackers = trackers
            .mapNotNull { trackerManager.get(it.toLong()) }
            .filter { !it.isLoggedIn }
            .map { it.name }
            .sorted()

        return Results(
            missingSources = missingSources,
            missingTrackers = missingTrackers,
            animeCount = backup.backupAnime.size,
            mangaCount = backup.backupManga.size,
            episodeCount = backup.backupAnime.sumOf { it.episodes.size },
            chapterCount = backup.backupManga.sumOf { it.chapters.size },
            extensionCount = backup.backupExtensions.size,
            settingCount = backup.backupPreferences.size + backup.backupSourcePreferences.sumOf { it.prefs.size },
            containsPrivateSettings = backup.backupPreferences.any { Preference.isPrivate(it.key) } ||
                backup.backupSourcePreferences.any { group -> group.prefs.any { Preference.isPrivate(it.key) } },
        )
    }

    data class Results(
        val missingSources: List<String>,
        val missingTrackers: List<String>,
        val animeCount: Int,
        val mangaCount: Int,
        val episodeCount: Int,
        val chapterCount: Int,
        val extensionCount: Int,
        val settingCount: Int,
        val containsPrivateSettings: Boolean,
    )
}

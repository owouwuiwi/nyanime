package eu.kanade.tachiyomi.data.backup.restore

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupCustomButtons
import eu.kanade.tachiyomi.data.backup.models.BackupExtension
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionRepos
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupHiddenResumeState
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.restore.restorers.AnimeCategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.AnimeExtensionStoreRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.AnimeRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.CustomButtonRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.ExtensionsRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaCategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaExtensionRepoRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.entries.anime.repository.AnimeRepository
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupRestorer(
    private val context: Context,
    private val notifier: BackupNotifier,
    private val isSync: Boolean,
    private val onProgress: suspend (Int, Int) -> Unit = { _, _ -> },
    private val emergency: Boolean = false,

    private val animeCategoriesRestorer: AnimeCategoriesRestorer = AnimeCategoriesRestorer(),
    private val mangaCategoriesRestorer: MangaCategoriesRestorer = MangaCategoriesRestorer(),
    private val preferenceRestorer: PreferenceRestorer = PreferenceRestorer(context, reschedule = !emergency),
    private val animeExtensionStoreRestorer: AnimeExtensionStoreRestorer = AnimeExtensionStoreRestorer(),
    private val mangaExtensionRepoRestorer: MangaExtensionRepoRestorer = MangaExtensionRepoRestorer(),
    private val customButtonRestorer: CustomButtonRestorer = CustomButtonRestorer(),
    private val animeRestorer: AnimeRestorer = AnimeRestorer(),
    private val mangaRestorer: MangaRestorer = MangaRestorer(),
    private val extensionsRestorer: ExtensionsRestorer = ExtensionsRestorer(context, emergency = emergency),
    private val animeRepository: AnimeRepository = Injekt.get(),
    private val preferenceStore: PreferenceStore = Injekt.get(),
) {

    private var restoreAmount = 0
    private var restoreProgress = 0
    private val errors = mutableListOf<Pair<Date, String>>()

    /**
     * Mapping of source ID to source name from backup data
     */
    private var animeSourceMapping: Map<Long, String> = emptyMap()
    private var mangaSourceMapping: Map<Long, String> = emptyMap()

    suspend fun restore(uri: Uri, options: RestoreOptions): BackupRestoreResult {
        val startTime = System.currentTimeMillis()

        eu.kanade.tachiyomi.data.releases.ReleaseRestoreGuard.during {
            restoreFromFile(uri, options)
        }
        val time = System.currentTimeMillis() - startTime

        val logFile = writeErrorLog()

        if (!emergency) {
            notifier.showRestoreComplete(
                time,
                errors.size,
                logFile.parent,
                logFile.name,
                isSync,
            )
        }
        return BackupRestoreResult(errors.size, time, logFile.takeIf { it.isFile }?.path)
    }

    private suspend fun restoreFromFile(uri: Uri, options: RestoreOptions) {
        val backup = BackupDecoder(context).decode(uri)

        // Store source mapping for error messages
        val backupAnimeMaps = backup.backupAnimeSources
        animeSourceMapping = backupAnimeMaps.associate { it.sourceId to it.name }
        val backupMangaMaps = backup.backupSources
        mangaSourceMapping = backupMangaMaps.associate { it.sourceId to it.name }

        if (options.libraryEntries) {
            restoreAmount += backup.backupManga.size + backup.backupAnime.size
        }
        if (options.categories) {
            restoreAmount += 2 // +2 for anime and manga categories
        }
        if (options.appSettings) {
            restoreAmount += 1
        }
        if (options.extensionStores) {
            restoreAmount += backup.backupAnimeExtensionStores.size + backup.backupMangaExtensionRepo.size
        }
        if (options.customButtons) {
            restoreAmount += 1
        }
        if (options.sourceSettings) {
            restoreAmount += 1
        }
        if (options.extensions) {
            restoreAmount += 1
        }

        coroutineScope {
            if (options.categories) {
                restoreCategories(
                    backupAnimeCategories = backup.backupAnimeCategories,
                    backupMangaCategories = backup.backupCategories,
                ).join()
            }
            if (options.appSettings) {
                restoreAppPreferences(
                    backup.backupPreferences,
                    backup.backupAnimeCategories.takeIf { options.categories },
                    backup.backupCategories.takeIf { options.categories },
                ).join()
                backup.newsState?.let {
                    eu.kanade.tachiyomi.data.news.NewsStore(context).restore(it)
                    if (!emergency) Injekt.get<eu.kanade.tachiyomi.data.news.NewsRepository>().initialize()
                }
            }
            if (options.sourceSettings) {
                restoreSourcePreferences(backup.backupSourcePreferences).join()
            }
            if (options.libraryEntries) {
                restoreAnime(backup.backupAnime, if (options.categories) backup.backupAnimeCategories else emptyList())
                    .join()
                restoreManga(backup.backupManga, if (options.categories) backup.backupCategories else emptyList())
                    .join()
                val guidedTitles = backup.backupManga.mapTo(hashSetOf()) { it.source to it.url }
                if (options.appSettings) {
                    eu.kanade.tachiyomi.ui.reader.spread.SpreadStore.get(context).merge(
                        backup.readerSpreads.filter { (it.source to it.title) in guidedTitles },
                    )
                }
                nyanime.panels.GuidedReadingStore.get(context).merge(
                    backup.guidedReading.filter { record ->
                        (record.ref.source to record.ref.title) in guidedTitles
                    }.map { record ->
                        if (options.appSettings) {
                            record
                        } else {
                            record.copy(
                                corrections = nyanime.panels.GuidedReadingStore.get(context)
                                    .get(record.ref)?.corrections,
                            )
                        }
                    },
                )
                if (options.appSettings) {
                    backup.backupHiddenResume?.let { restoreHiddenResume(it) }
                    eu.kanade.tachiyomi.data.releases.ReleaseStore().restore(backup.releaseSubscriptions)
                    if (!emergency) eu.kanade.tachiyomi.data.releases.ReleaseMonitor.setup(context)
                }
            }
            if (options.extensionStores) {
                restoreExtensionStores(backup.backupAnimeExtensionStores, backup.backupMangaExtensionRepo).join()
            }
            if (options.customButtons) {
                restoreCustomButtons(backup.backupCustomButton).join()
            }
            if (options.extensions) {
                restoreExtensions(backup.backupExtensions).join()
            }

            // TODO: optionally trigger online library + tracker update
        }
    }

    private suspend fun restoreHiddenResume(state: BackupHiddenResumeState) {
        val ids = state.entries.mapNotNull { entry ->
            val id = try {
                animeRepository.getAnimeByUrlAndSourceId(entry.url, entry.source)?.id?.toString()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (id == null) {
                errors.add(Date() to "Hidden title unavailable [${entry.source}]: ${entry.url}")
            }
            id
        }
        preferenceStore.getStringSet(Preference.appStateKey("discovery_hidden_resume")).set(ids.toSet())
    }

    private fun CoroutineScope.restoreCategories(
        backupAnimeCategories: List<BackupCategory>,
        backupMangaCategories: List<BackupCategory>,
    ) = launch {
        ensureActive()
        animeCategoriesRestorer(backupAnimeCategories)
        mangaCategoriesRestorer(backupMangaCategories)

        restoreProgress += 1
        reportProgress(
            context.stringResource(MR.strings.categories),
            restoreProgress,
            restoreAmount,
            isSync,
        )
    }

    private fun CoroutineScope.restoreAnime(
        backupAnimes: List<BackupAnime>,
        backupAnimeCategories: List<BackupCategory>,
    ) = launch {
        val idsInBackup = backupAnimes.mapNotNull { it.id }.toSet()
        val seasonsByParent = backupAnimes.filter { it.parentId in idsInBackup }.groupBy { it.parentId }
        animeRestorer.sortByNew(backupAnimes.filter { it.parentId !in idsInBackup })
            .forEach {
                ensureActive()

                val seasons = seasonsByParent[it.id].orEmpty()
                try {
                    animeRestorer.restore(it, backupAnimeCategories, seasons)
                } catch (e: Exception) {
                    val sourceName = animeSourceMapping[it.source] ?: it.source.toString()
                    errors.add(Date() to "${it.title} [$sourceName]: ${e.message}")
                }

                restoreProgress += 1 + seasons.size
                reportProgress(it.title, restoreProgress, restoreAmount, isSync)
            }
    }

    private fun CoroutineScope.restoreManga(
        backupMangas: List<BackupManga>,
        backupMangaCategories: List<BackupCategory>,
    ) = launch {
        mangaRestorer.sortByNew(backupMangas)
            .forEach {
                ensureActive()

                try {
                    mangaRestorer.restore(it, backupMangaCategories)
                } catch (e: Exception) {
                    val sourceName = mangaSourceMapping[it.source] ?: it.source.toString()
                    errors.add(Date() to "${it.title} [$sourceName]: ${e.message}")
                }

                restoreProgress += 1
                reportProgress(it.title, restoreProgress, restoreAmount, isSync)
            }
    }

    private fun CoroutineScope.restoreAppPreferences(
        preferences: List<BackupPreference>,
        animeCategories: List<BackupCategory>?,
        mangaCategories: List<BackupCategory>?,
    ) = launch {
        ensureActive()
        preferenceRestorer.restoreApp(
            preferences,
            animeCategories,
            mangaCategories,
        ).forEach { key -> errors.add(Date() to "App setting: $key") }

        restoreProgress += 1
        reportProgress(
            context.stringResource(MR.strings.app_settings),
            restoreProgress,
            restoreAmount,
            isSync,
        )
    }

    private fun CoroutineScope.restoreSourcePreferences(preferences: List<BackupSourcePreferences>) = launch {
        ensureActive()
        preferenceRestorer.restoreSource(preferences)
            .forEach { key -> errors.add(Date() to "Source setting: $key") }

        restoreProgress += 1
        reportProgress(
            context.stringResource(MR.strings.source_settings),
            restoreProgress,
            restoreAmount,
            isSync,
        )
    }

    private fun CoroutineScope.restoreExtensionStores(
        backupAnimeExtensionStores: List<BackupExtensionStore>,
        backupMangaExtensionRepo: List<BackupExtensionRepos>,
    ) = launch {
        backupAnimeExtensionStores
            .forEach {
                ensureActive()

                try {
                    animeExtensionStoreRestorer(it)
                } catch (e: Exception) {
                    errors.add(Date() to "Error Adding Anime Repo: ${it.name} : ${e.message}")
                }

                restoreProgress += 1
                reportProgress(
                    context.stringResource(MR.strings.extensionStores),
                    restoreProgress,
                    restoreAmount,
                    isSync,
                )
            }

        backupMangaExtensionRepo
            .forEach {
                ensureActive()

                try {
                    mangaExtensionRepoRestorer(it)
                } catch (e: Exception) {
                    errors.add(Date() to "Error Adding Manga Repo: ${it.name} : ${e.message}")
                }

                restoreProgress += 1
                reportProgress(
                    context.stringResource(MR.strings.extensionRepo_settings),
                    restoreProgress,
                    restoreAmount,
                    isSync,
                )
            }
    }

    private fun CoroutineScope.restoreCustomButtons(customButtons: List<BackupCustomButtons>) = launch {
        ensureActive()
        customButtonRestorer(customButtons)

        restoreProgress += 1
        reportProgress(
            context.stringResource(AYMR.strings.custom_button_settings),
            restoreProgress,
            restoreAmount,
            isSync,
        )
    }

    private fun CoroutineScope.restoreExtensions(extensions: List<BackupExtension>) = launch {
        ensureActive()
        extensionsRestorer.restoreExtensions(extensions).forEach { errors.add(Date() to "Extension: $it") }

        restoreProgress += 1
        reportProgress(
            context.stringResource(MR.strings.source_settings),
            restoreProgress,
            restoreAmount,
            isSync,
        )
    }

    private suspend fun reportProgress(content: String, progress: Int, total: Int, sync: Boolean) {
        onProgress(progress, total)
        if (!emergency) notifier.showRestoreProgress(content, progress, total, sync)
    }

    private fun writeErrorLog(): File {
        try {
            if (errors.isNotEmpty()) {
                val file = context.createFileInCacheDir("nyanime_restore_error.txt")
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

                file.bufferedWriter().use { out ->
                    errors.forEach { (date, message) ->
                        out.write("[${sdf.format(date)}] $message\n")
                    }
                }
                return file
            }
        } catch (e: Exception) {
            // Empty
        }
        return File("")
    }
}

data class BackupRestoreResult(val errorCount: Int, val durationMillis: Long, val errorLogPath: String?)

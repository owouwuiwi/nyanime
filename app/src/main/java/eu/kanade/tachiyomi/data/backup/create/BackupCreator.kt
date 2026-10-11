package eu.kanade.tachiyomi.data.backup.create

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.data.backup.BackupFileValidator
import eu.kanade.tachiyomi.data.backup.create.creators.AnimeBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.AnimeCategoriesBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.AnimeExtensionStoresBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.AnimeSourcesBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.CustomButtonBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.ExtensionsBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.MangaCategoriesBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.MangaExtensionRepoBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.MangaSourcesBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.PreferenceBackupCreator
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import eu.kanade.tachiyomi.data.backup.models.BackupAnimeSource
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupCustomButtons
import eu.kanade.tachiyomi.data.backup.models.BackupExtension
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionRepos
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupHiddenResume
import eu.kanade.tachiyomi.data.backup.models.BackupHiddenResumeState
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import okio.buffer
import okio.gzip
import okio.sink
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.entries.anime.interactor.GetAnimeFavorites
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.repository.AnimeRepository
import tachiyomi.domain.entries.manga.interactor.GetMangaFavorites
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.entries.manga.repository.MangaRepository
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

class BackupCreator(
    private val context: Context,
    private val isAutoBackup: Boolean,
    private val emergency: Boolean = false,
    private val parser: ProtoBuf = Injekt.get(),
    private val getAnimeFavorites: GetAnimeFavorites = Injekt.get(),
    private val getMangaFavorites: GetMangaFavorites = Injekt.get(),
    private val backupPreferences: BackupPreferences = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val animeRepository: AnimeRepository = Injekt.get(),
    private val preferenceStore: PreferenceStore = Injekt.get(),
    private val animeCategoriesBackupCreator: AnimeCategoriesBackupCreator =
        AnimeCategoriesBackupCreator(),
    private val mangaCategoriesBackupCreator: MangaCategoriesBackupCreator =
        MangaCategoriesBackupCreator(),
    private val animeBackupCreator: AnimeBackupCreator = AnimeBackupCreator(),
    private val mangaBackupCreator: MangaBackupCreator = MangaBackupCreator(),
    private val preferenceBackupCreator: PreferenceBackupCreator =
        if (emergency) {
            PreferenceBackupCreator(null, null, offlineContext = context)
        } else {
            PreferenceBackupCreator()
        },
    private val animeExtensionStoresBackupCreator: AnimeExtensionStoresBackupCreator =
        AnimeExtensionStoresBackupCreator(),
    private val mangaExtensionRepoBackupCreator: MangaExtensionRepoBackupCreator =
        MangaExtensionRepoBackupCreator(),
    private val customButtonBackupCreator: CustomButtonBackupCreator = CustomButtonBackupCreator(),
    private val animeSourcesBackupCreator: AnimeSourcesBackupCreator =
        if (emergency) {
            AnimeSourcesBackupCreator(null)
        } else {
            AnimeSourcesBackupCreator()
        },
    private val mangaSourcesBackupCreator: MangaSourcesBackupCreator =
        if (emergency) {
            MangaSourcesBackupCreator(null)
        } else {
            MangaSourcesBackupCreator()
        },
    private val extensionsBackupCreator: ExtensionsBackupCreator =
        if (emergency) {
            ExtensionsBackupCreator(context, null, null)
        } else {
            ExtensionsBackupCreator(context)
        },
) {

    suspend fun backup(
        uri: Uri,
        options: BackupOptions,
        onPhase: suspend (String) -> Unit = {},
    ): String {
        var file: UniFile? = null
        try {
            file =
                if (isAutoBackup) {
                    // Get dir of file and create
                    val dir = UniFile.fromUri(context, uri)
                    // Create new file to place backup
                    dir?.createFile(getFilename())
                } else {
                    UniFile.fromUri(context, uri)
                }

            if (isAutoBackup && (file == null || !file.isFile)) {
                throw IllegalStateException(
                    context.stringResource(MR.strings.create_backup_file_error),
                )
            }
            val fileUri = file?.uri ?: uri

            val nonFavoriteAnime =
                if (options.readEntries) {
                    animeRepository.getWatchedAnimeNotInLibrary()
                } else {
                    emptyList()
                }
            val hiddenResume = backupHiddenResume(options)
            val hiddenAnime =
                hiddenResume?.entries.orEmpty().mapNotNull { entry ->
                    animeRepository.getAnimeByUrlAndSourceId(entry.url, entry.source)
                }
            val releaseStore = eu.kanade.tachiyomi.data.releases.ReleaseStore()
            val subscriptions = if (options.appSettings) releaseStore.backup() else emptyList()
            val followedAnime =
                subscriptions
                    .filter { it.medium == "ANIME" }
                    .mapNotNull {
                        animeRepository.getAnimeByUrlAndSourceId(it.url, it.source)
                    }
            val followedManga =
                subscriptions
                    .filter { it.medium == "MANGA" }
                    .mapNotNull {
                        mangaRepository.getMangaByUrlAndSourceId(it.url, it.source)
                    }
            val animeEntries =
                getAnimeFavorites.await() + nonFavoriteAnime + hiddenAnime + followedAnime
            val seasons =
                animeEntries
                    .filter { it.fetchType == FetchType.Seasons }
                    .flatMap {
                        animeRepository.getAnimeSeasonsById(it.id).map { season -> season.anime }
                    }
            val backupAnime = backupAnimes((animeEntries + seasons).distinctBy { it.id }, options)
            val nonFavoriteManga =
                if (options.readEntries) {
                    mangaRepository.getReadMangaNotInLibrary()
                } else {
                    emptyList()
                }
            val backupManga =
                backupMangas(
                    (getMangaFavorites.await() + nonFavoriteManga + followedManga).distinctBy {
                        it.id
                    },
                    options,
                )

            val guidedTitles = backupManga.mapTo(hashSetOf()) { it.source to it.url }
            val backup =
                Backup(
                    backupManga = backupManga,
                    novelMetadata = it.lorelore.nyanime.novel.NovelBackup.create(context, options),
                    readerSpreads =
                    if (options.appSettings) {
                        eu.kanade.tachiyomi.ui.reader.spread.SpreadStore.get(context)
                            .snapshot()
                            .filter {
                                (it.source to it.title) in guidedTitles
                            }
                    } else {
                        emptyList()
                    },
                    guidedReading =
                    nyanime.panels.GuidedReadingStore.get(context)
                        .snapshot()
                        .filter { record ->
                            (record.ref.source to record.ref.title) in guidedTitles
                        }
                        .map { record ->
                            record.copy(
                                corrections = record.corrections.takeIf { options.appSettings },
                                location = record.location.takeIf { options.chapters },
                            )
                        }
                        .filter { it.corrections != null || it.location != null },
                    backupCategories = backupMangaCategories(options),
                    backupSources = backupMangaSources(backupManga),
                    backupPreferences = backupAppPreferences(options),
                    backupSourcePreferences = backupSourcePreferences(options),
                    backupMangaExtensionRepo = backupMangaExtensionRepos(options),
                    isLegacy = false,
                    backupAnime = backupAnime,
                    backupAnimeCategories = backupAnimeCategories(options),
                    backupAnimeSources = backupAnimeSources(backupAnime),
                    backupExtensions = backupExtensions(options),
                    backupAnimeExtensionStores = backupAnimeExtensionStores(options),
                    backupCustomButton = backupCustomButtons(options),
                    backupHiddenResume = hiddenResume,
                    releaseSubscriptions = subscriptions,
                    newsState =
                    if (options.appSettings) {
                        eu.kanade.tachiyomi.data.news.NewsStore(context).backup()
                    } else {
                        null
                    },
                    sourceStudioState =
                    if (options.sourceSettings) {
                        it.lorelore.nyanime.studio.SourceStudioRepository.get(context).backup()
                    } else {
                        null
                    },
                    personalTracking =
                    if (options.libraryEntries && options.tracking) {
                        val identities =
                            backupAnime
                                .map { entry ->
                                    Triple(
                                        it.lorelore.nyanime.tracking.TrackingMedium.ANIME,
                                        entry.source,
                                        entry.url,
                                    )
                                }
                                .toSet() +
                                backupManga.map { entry ->
                                    Triple(
                                        it.lorelore.nyanime.tracking.TrackingMedium.MANGA,
                                        entry.source,
                                        entry.url,
                                    )
                                }
                        it.lorelore.nyanime.tracking.PersonalTrackingStore.get(context)
                            .snapshot()
                            .filter { record ->
                                Triple(
                                    record.subject.medium,
                                    record.subject.source,
                                    record.subject.url,
                                ) in identities
                            }
                    } else {
                        emptyList()
                    },
                )

            val byteArray = parser.encodeToByteArray(Backup.serializer(), backup)
            if (byteArray.isEmpty()) {
                throw IllegalStateException(context.stringResource(MR.strings.empty_backup_error))
            }

            onPhase("saving")
            val output =
                if (isAutoBackup) {
                    file?.openOutputStream()
                } else {
                    context.contentResolver.openOutputStream(
                        fileUri,
                        "wt",
                    )
                }
            (
                output
                    ?: throw IllegalStateException(
                        context.stringResource(MR.strings.create_backup_file_error),
                    )
                )
                .also {
                    // Force overwrite old file
                    (it as? FileOutputStream)?.channel?.truncate(0)
                }
                .sink()
                .gzip()
                .buffer()
                .use {
                    it.write(byteArray)
                }

            // Make sure it's a valid backup file
            onPhase("verifying")
            if (emergency) {
                val decoded = eu.kanade.tachiyomi.data.backup.BackupDecoder(context).decode(fileUri)
                check(
                    decoded.backupAnime.size == backupAnime.size &&
                        decoded.backupManga.size == backupManga.size,
                )
                check(decoded.backupExtensions.size == backup.backupExtensions.size)
            } else {
                BackupFileValidator(context).validate(fileUri)
            }

            if (isAutoBackup) {
                backupPreferences.lastAutoBackupTimestamp().set(Instant.now().toEpochMilli())
                // Retention cannot invalidate an already verified replacement.
                runCatching {
                    val directory = UniFile.fromUri(context, uri)
                    val files =
                        directory
                            ?.listFiles { _, filename -> FILENAME_REGEX.matches(filename) }
                            .orEmpty()
                            .sortedByDescending { it.name }
                    val obsolete = BackupRetention.obsolete(files.map { it.uri }, fileUri)
                    files.filter { it.uri in obsolete }.forEach { it.delete() }
                }
                    .onFailure { logcat(LogPriority.WARN, it) }
            }

            return fileUri.toString()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            file?.delete()
            throw e
        }
    }

    private suspend fun backupAnimeCategories(options: BackupOptions): List<BackupCategory> {
        if (!options.categories) return emptyList()

        return animeCategoriesBackupCreator()
    }

    private suspend fun backupMangaCategories(options: BackupOptions): List<BackupCategory> {
        if (!options.categories) return emptyList()

        return mangaCategoriesBackupCreator()
    }

    private suspend fun backupMangas(
        mangas: List<Manga>,
        options: BackupOptions,
    ): List<BackupManga> {
        if (!options.libraryEntries) return emptyList()

        return mangaBackupCreator(mangas, options)
    }

    private suspend fun backupAnimes(
        animes: List<Anime>,
        options: BackupOptions,
    ): List<BackupAnime> {
        if (!options.libraryEntries) return emptyList()

        return animeBackupCreator(animes, options)
    }

    private fun backupAnimeSources(animes: List<BackupAnime>): List<BackupAnimeSource> {
        return animeSourcesBackupCreator(animes)
    }

    private fun backupMangaSources(mangas: List<BackupManga>): List<BackupSource> {
        return mangaSourcesBackupCreator(mangas)
    }

    private fun backupAppPreferences(options: BackupOptions): List<BackupPreference> {
        if (!options.appSettings) return emptyList()

        return preferenceBackupCreator.createApp(
            includePrivatePreferences = options.privateSettings,
        )
    }

    private suspend fun backupAnimeExtensionStores(
        options: BackupOptions,
    ): List<BackupExtensionStore> {
        if (!options.extensionStores) return emptyList()

        return animeExtensionStoresBackupCreator()
    }

    private suspend fun backupMangaExtensionRepos(
        options: BackupOptions,
    ): List<BackupExtensionRepos> {
        if (!options.extensionStores) return emptyList()

        return mangaExtensionRepoBackupCreator()
    }

    private suspend fun backupCustomButtons(options: BackupOptions): List<BackupCustomButtons> {
        if (!options.customButton) return emptyList()

        return customButtonBackupCreator()
    }

    private suspend fun backupHiddenResume(options: BackupOptions): BackupHiddenResumeState? {
        if (!options.appSettings || !options.libraryEntries) return null

        val hiddenIds =
            preferenceStore.getStringSet(Preference.appStateKey("discovery_hidden_resume")).get()
        val entries = hiddenIds.mapNotNull { id ->
            val anime =
                try {
                    animeRepository.getAnimeById(id.toLong())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            anime?.let { BackupHiddenResume(source = it.source, url = it.url) }
        }
        return BackupHiddenResumeState(entries)
    }

    private fun backupSourcePreferences(options: BackupOptions): List<BackupSourcePreferences> {
        if (!options.sourceSettings) return emptyList()

        return preferenceBackupCreator.createSource(
            includePrivatePreferences = options.privateSettings,
        )
    }

    private fun backupExtensions(options: BackupOptions): List<BackupExtension> {
        if (!options.extensions) return emptyList()

        return extensionsBackupCreator()
    }

    companion object {
        private val FILENAME_REGEX =
            """${Regex.escape(
                BuildConfig.APPLICATION_ID,
            )}_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}(?:-\d{2}-\d{3})?\.nyabk"""
                .toRegex()

        fun getFilename(): String {
            val date = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.ENGLISH).format(Date())
            return "${BuildConfig.APPLICATION_ID}_$date.nyabk"
        }
    }
}

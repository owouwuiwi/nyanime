package eu.kanade.tachiyomi.data.backup.restore.restorers

import android.content.Context
import android.util.Log
import eu.kanade.tachiyomi.data.backup.BackupPreferencePolicy
import eu.kanade.tachiyomi.data.backup.create.BackupCreateJob
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.FloatPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.LongPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.data.library.anime.AnimeLibraryUpdateJob
import eu.kanade.tachiyomi.data.library.manga.MangaLibraryUpdateJob
import eu.kanade.tachiyomi.source.sourcePreferences
import kotlinx.coroutines.CancellationException
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.category.anime.interactor.GetAnimeCategories
import tachiyomi.domain.category.manga.interactor.GetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class PreferenceRestorer(
    private val context: Context,
    private val getMangaCategories: GetMangaCategories = Injekt.get(),
    private val getAnimeCategories: GetAnimeCategories = Injekt.get(),
    private val preferenceStore: PreferenceStore = Injekt.get(),
    private val reschedule: Boolean = true,
) {
    suspend fun restoreApp(
        preferences: List<BackupPreference>,
        backupAnimeCategories: List<BackupCategory>?,
        backupMangaCategories: List<BackupCategory>?,
    ): List<String> {
        val failures = restorePreferences(
            preferences,
            preferenceStore,
            backupAnimeCategories,
            backupMangaCategories,
        )

        if (reschedule) {
            AnimeLibraryUpdateJob.setupTask(context)
            MangaLibraryUpdateJob.setupTask(context)
            BackupCreateJob.setupTask(context)
        }
        return failures
    }

    suspend fun restoreSource(preferences: List<BackupSourcePreferences>): List<String> {
        return preferences.flatMap {
            val sourcePrefs = AndroidPreferenceStore(context, sourcePreferences(it.sourceKey))
            restorePreferences(it.prefs, sourcePrefs).map { key -> "${it.sourceKey}: $key" }
        }
    }

    private suspend fun restorePreferences(
        toRestore: List<BackupPreference>,
        preferenceStore: PreferenceStore,
        backupAnimeCategories: List<BackupCategory>? = null,
        backupMangaCategories: List<BackupCategory>? = null,
    ): List<String> {
        val failures = mutableListOf<String>()
        val allMangaCategories = if (backupMangaCategories != null) getMangaCategories.await() else emptyList()
        val allAnimeCategories = if (backupAnimeCategories != null) getAnimeCategories.await() else emptyList()

        val mangaCategoriesByName = allMangaCategories.associateBy { it.name }
        val animeCategoriesByName = allAnimeCategories.associateBy { it.name }
        val backupMangaCategoriesById = backupMangaCategories?.associateBy { it.id.toString() }.orEmpty()
        val backupAnimeCategoriesById = backupAnimeCategories?.associateBy { it.id.toString() }.orEmpty()

        toRestore.forEach { (key, value) ->
            if (!BackupPreferencePolicy.isPortable(key)) return@forEach
            try {
                when (value) {
                    is IntPreferenceValue -> {
                        val newValue = if (key == LibraryPreferences.DEFAULT_MANGA_CATEGORY_PREF_KEY) {
                            if (backupMangaCategories != null && value.value == -1) {
                                -1
                            } else {
                                backupMangaCategoriesById[value.value.toString()]
                                    ?.let { mangaCategoriesByName[it.name]?.id?.toInt() }
                            }
                        } else if (key == LibraryPreferences.DEFAULT_ANIME_CATEGORY_PREF_KEY) {
                            if (backupAnimeCategories != null && value.value == -1) {
                                -1
                            } else {
                                backupAnimeCategoriesById[value.value.toString()]
                                    ?.let { animeCategoriesByName[it.name]?.id?.toInt() }
                            }
                        } else {
                            value.value
                        }

                        if (newValue == null) {
                            failures += key
                        } else {
                            preferenceStore.getInt(key).set(newValue)
                        }
                    }
                    is LongPreferenceValue -> preferenceStore.getLong(key).set(value.value)
                    is FloatPreferenceValue -> preferenceStore.getFloat(key).set(value.value)
                    is StringPreferenceValue -> preferenceStore.getString(key).set(value.value)
                    is BooleanPreferenceValue -> preferenceStore.getBoolean(key).set(value.value)
                    is StringSetPreferenceValue -> {
                        val restored = restoreCategoriesPreference(
                            key,
                            value.value,
                            preferenceStore,
                            backupMangaCategoriesById,
                            backupAnimeCategoriesById,
                            mangaCategoriesByName,
                            animeCategoriesByName,
                            backupMangaCategories != null,
                            backupAnimeCategories != null,
                        )
                        if (!restored) preferenceStore.getStringSet(key).set(value.value)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("PreferenceRestorer", "Failed to restore preference <$key>", e)
                failures += key
            }
        }
        return failures
    }

    private fun restoreCategoriesPreference(
        key: String,
        value: Set<String>,
        preferenceStore: PreferenceStore,
        backupMangaCategoriesById: Map<String, BackupCategory>,
        backupAnimeCategoriesById: Map<String, BackupCategory>,
        mangaCategoriesByName: Map<String, Category>,
        animeCategoriesByName: Map<String, Category>,
        restoreMangaCategories: Boolean,
        restoreAnimeCategories: Boolean,
    ): Boolean {
        val isAnime = key in LibraryPreferences.animeCategoryPreferenceKeys ||
            key in DownloadPreferences.animeCategoryPreferenceKeys
        val isManga = key in LibraryPreferences.mangaCategoryPreferenceKeys ||
            key in DownloadPreferences.mangaCategoryPreferenceKeys
        if (!isAnime && !isManga) return false
        if ((isAnime && !restoreAnimeCategories) || (isManga && !restoreMangaCategories)) return true

        val backupById = if (isAnime) backupAnimeCategoriesById else backupMangaCategoriesById
        val restoredByName = if (isAnime) animeCategoriesByName else mangaCategoriesByName
        val ids = value.mapNotNull { backupById[it]?.name?.let { name -> restoredByName[name]?.id?.toString() } }
        preferenceStore.getStringSet(key).set(ids.toSet())
        return true
    }
}

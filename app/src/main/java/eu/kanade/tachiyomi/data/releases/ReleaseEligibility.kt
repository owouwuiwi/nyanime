package eu.kanade.tachiyomi.data.releases

import eu.kanade.domain.source.anime.interactor.GetAnimeIncognitoState
import eu.kanade.domain.source.manga.interactor.GetMangaIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.discovery.ResumeVisibility
import tachiyomi.domain.category.anime.repository.AnimeCategoryRepository
import tachiyomi.domain.category.manga.repository.MangaCategoryRepository
import tachiyomi.domain.entries.anime.repository.AnimeRepository
import tachiyomi.domain.entries.manga.repository.MangaRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.source.manga.service.MangaSourceManager
import tachiyomi.source.local.entries.anime.isLocal
import tachiyomi.source.local.entries.manga.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** One eligibility rule for polling, calendar and notifications. Explicit exclusions win. */
object ReleaseEligibility {
    suspend fun source(medium: ReleaseMedium, id: Long): Long? {
        val mode = ReleaseStore().subscription(medium, id).mode
        if (mode == FollowMode.IGNORE) return null
        val library: LibraryPreferences = Injekt.get()
        val sources: SourcePreferences = Injekt.get()
        val sourceId: Long
        val categories: Set<String>
        val included: Set<String>
        val excluded: Set<String>
        when (medium) {
            ReleaseMedium.ANIME -> {
                val entry = Injekt.get<AnimeRepository>().getAnimeById(id)
                if (entry.isLocal() ||
                    id.toString() in Injekt.get<ResumeVisibility>().hidden.get() ||
                    Injekt.get<GetAnimeIncognitoState>().await(entry.source) ||
                    Injekt.get<AnimeSourceManager>().get(entry.source) == null ||
                    entry.source.toString() in sources.disabledAnimeSources().get()
                ) {
                    return null
                }
                val parent = entry.parentId?.takeIf { it > 0 }
                if (parent != null) {
                    if (parent.toString() in Injekt.get<ResumeVisibility>().hidden.get()) return null
                    if (mode == FollowMode.AUTO &&
                        ReleaseStore().subscription(medium, parent).mode == FollowMode.IGNORE
                    ) {
                        return null
                    }
                }
                sourceId = entry.source
                val categoryRepository: AnimeCategoryRepository = Injekt.get()
                val assigned = categoryRepository.getCategoriesByAnimeId(id).ifEmpty {
                    if (parent == null) emptyList() else categoryRepository.getCategoriesByAnimeId(parent)
                }
                categories = assigned.map {
                    it.id.toString()
                }.toSet().ifEmpty { setOf("0") }
                included = library.animeUpdateCategories().get()
                excluded = library.animeUpdateCategoriesExclude().get()
            }
            ReleaseMedium.MANGA -> {
                val entry = Injekt.get<MangaRepository>().getMangaById(id)
                if (entry.isLocal() ||
                    Injekt.get<GetMangaIncognitoState>().await(entry.source) ||
                    Injekt.get<MangaSourceManager>().get(entry.source) == null ||
                    entry.source.toString() in sources.disabledMangaSources().get()
                ) {
                    return null
                }
                sourceId = entry.source
                categories =
                    Injekt.get<MangaCategoryRepository>().getCategoriesByMangaId(id).map {
                        it.id.toString()
                    }.toSet().ifEmpty { setOf("0") }
                included = library.mangaUpdateCategories().get()
                excluded = library.mangaUpdateCategoriesExclude().get()
            }
        }
        if (categories.any { it in excluded }) return null
        if (mode != FollowMode.FOLLOW && included.isNotEmpty() && categories.none { it in included }) return null
        return sourceId
    }
}

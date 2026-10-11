package eu.kanade.tachiyomi.data.track

import eu.kanade.domain.source.anime.interactor.GetAnimeIncognitoState
import eu.kanade.domain.source.manga.interactor.GetMangaIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.source.CatalogueSource

class ContinuitySourcePolicy(
    private val preferences: SourcePreferences,
    private val uiPreferences: UiPreferences,
    private val animeIncognito: GetAnimeIncognitoState,
    private val mangaIncognito: GetMangaIncognitoState,
) {
    fun privateAnime(source: Long) = animeIncognito.await(source)
    fun privateManga(source: Long) = mangaIncognito.await(source)

    fun manga(sources: List<CatalogueSource>): List<CatalogueSource> {
        val languages = preferences.enabledLanguages().get()
        val disabled = preferences.disabledMangaSources().get()
        val pinned = preferences.pinnedMangaSources().get()
        return sources.filter {
            it.lang in languages &&
                it.id.toString() !in disabled &&
                (uiPreferences.showMangaInOtherLanguages().get() || it.lang == "it")
        }.sortedWith(compareBy({ it.id.toString() !in pinned }, { it.name }, { it.id }))
    }

    fun anime(sources: List<AnimeCatalogueSource>): List<AnimeCatalogueSource> {
        val languages = preferences.enabledLanguages().get()
        val disabled = preferences.disabledAnimeSources().get()
        val pinned = preferences.pinnedAnimeSources().get()
        return sources.filter { it.lang in languages && it.id.toString() !in disabled }
            .sortedWith(compareBy({ it.id.toString() !in pinned }, { it.name }, { it.id }))
    }
}

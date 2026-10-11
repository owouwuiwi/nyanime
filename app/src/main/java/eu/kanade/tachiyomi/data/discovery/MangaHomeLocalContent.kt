package eu.kanade.tachiyomi.data.discovery

import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.manga.interactor.GetMangaIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.ui.updates.inboxKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import tachiyomi.domain.history.manga.model.MangaHistoryWithRelations
import tachiyomi.domain.source.manga.service.MangaSourceManager
import tachiyomi.domain.updates.manga.model.MangaUpdatesWithRelations

/** Local Home rows. Filtering never changes stored history or reading progress. */
internal class MangaHomeLocalContent(
    private val manager: MangaSourceManager,
    private val preferences: SourcePreferences,
    private val base: BasePreferences,
    private val incognito: GetMangaIncognitoState,
    private val uiPreferences: UiPreferences,
) {
    // Database rows can arrive before source registration after an upgrade/cold start.
    // Reevaluate them when sources become ready or are replaced, without another DB write.
    private fun <T> sourceAware(entries: Flow<List<T>>) = combine(
        entries,
        manager.catalogueSources,
        manager.isInitialized,
    ) { rows, _, initialized -> if (initialized) rows else emptyList() }

    fun history(entries: Flow<List<MangaHistoryWithRelations>>, limit: Int = 20) = combine(
        sourceAware(entries),
        preferences.disabledMangaSources().changes(),
        preferences.enabledLanguages().changes(),
        base.incognitoMode().changes(),
        combine(
            preferences.incognitoMangaExtensions().changes(),
            uiPreferences.showMangaInOtherLanguages().changes(),
        ) { _, showOtherLanguages -> showOtherLanguages },
    ) { history, disabled, languages, private, showOtherLanguages ->
        if (private) {
            emptyList()
        } else {
            history.filter {
                it.coverData.sourceId.toString() !in disabled &&
                    manager.get(it.coverData.sourceId)?.lang in languages &&
                    (showOtherLanguages || manager.get(it.coverData.sourceId)?.lang == "it") &&
                    !incognito.await(it.coverData.sourceId)
            }.distinctBy { it.mangaId }.take(limit)
        }
    }.distinctUntilChanged()

    fun updates(entries: Flow<List<MangaUpdatesWithRelations>>, limit: Int = 30) = combine(
        sourceAware(entries),
        uiPreferences.dismissedLibraryUpdates().changes(),
        base.incognitoMode().changes(),
        uiPreferences.showMangaInOtherLanguages().changes(),
    ) { updates, dismissed, private, showOtherLanguages ->
        if (private) {
            emptyList()
        } else {
            updates
                .distinctBy { it.mangaId }
                .filter { showOtherLanguages || manager.get(it.sourceId)?.lang == "it" }
                .filterNot { it.read || it.inboxKey() in dismissed }
                .take(limit)
        }
    }.distinctUntilChanged()
}

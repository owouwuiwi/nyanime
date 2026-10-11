package eu.kanade.tachiyomi.ui.privacy

import eu.kanade.domain.source.anime.interactor.GetAnimeIncognitoState
import eu.kanade.domain.source.manga.interactor.GetMangaIncognitoState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.model.AnimeCover
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.entries.manga.model.MangaCover
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class PrivacyContentReference(val media: PrivacyMedia, val sourceId: Long) {
    companion object {
        fun from(data: Any?): PrivacyContentReference? = when (data) {
            is Anime -> PrivacyContentReference(PrivacyMedia.VIDEO, data.source)
            is Manga -> PrivacyContentReference(PrivacyMedia.MANGA, data.source)
            is AnimeCover -> PrivacyContentReference(PrivacyMedia.VIDEO, data.sourceId)
            is MangaCover -> PrivacyContentReference(PrivacyMedia.MANGA, data.sourceId)
            else -> null
        }
    }
}

/** Reuses incognito semantics; it does not change history, tracking or extension configuration. */
class PrivacyIncognitoResolver(
    private val anime: GetAnimeIncognitoState,
    private val manga: GetMangaIncognitoState,
) {
    fun current(reference: PrivacyContentReference): Boolean = when (reference.media) {
        PrivacyMedia.VIDEO -> anime.await(reference.sourceId)
        PrivacyMedia.MANGA -> manga.await(reference.sourceId)
    }

    fun subscribe(media: PrivacyMedia, sourceId: Long?): Flow<Boolean> = when (media) {
        PrivacyMedia.VIDEO -> anime.subscribe(sourceId)
        PrivacyMedia.MANGA -> manga.subscribe(sourceId)
    }
}

/** The controller receives only a boolean stream, not a source or a media model. */
fun PrivacyDisplayController.observeContentIncognito(
    scope: PrivacyArea,
    media: PrivacyMedia,
    sourceIds: Flow<Long?>,
    owner: Any? = null,
) {
    val resolver by lazy { Injekt.get<PrivacyIncognitoResolver>() }
    observeIncognito(
        scope,
        sourceIds.distinctUntilChanged().flatMapLatest { resolver.subscribe(media, it) },
        owner,
    )
}

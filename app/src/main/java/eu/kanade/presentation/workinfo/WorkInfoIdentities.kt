package eu.kanade.presentation.workinfo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import eu.kanade.tachiyomi.data.discovery.MangaHomeItem
import eu.kanade.tachiyomi.data.discovery.MangaHomeService
import eu.kanade.tachiyomi.data.track.SourceTrackingHints
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.workinfo.SourceWorkIdentity
import eu.kanade.tachiyomi.data.workinfo.WorkIdentity
import eu.kanade.tachiyomi.data.workinfo.WorkInfoPreferences
import eu.kanade.tachiyomi.data.workinfo.WorkKind
import eu.kanade.tachiyomi.data.workinfo.WorkProvider
import eu.kanade.tachiyomi.data.workinfo.WorkReference
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreenModel
import eu.kanade.tachiyomi.ui.entries.manga.MangaScreenModel
import kotlinx.serialization.json.JsonObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
internal fun rememberAnimeWorkInfo(state: AnimeScreenModel.State.Success): WorkInfoState {
    val anime = state.anime
    val identity = remember(anime.source, anime.url, anime.title, anime.memo, state.trackItems) {
        val hints = SourceTrackingHints.from(anime)
        val ids =
            listOfNotNull(hints?.anilistId) +
                state.trackItems.filter { it.tracker.id == TrackerManager.ANILIST }.mapNotNull { it.track?.remoteId }
        SourceWorkIdentity.apply(
            WorkIdentity(
                WorkInfoPreferences.localKey(false, anime.source, anime.url),
                anime.title,
                WorkKind.VIDEO,
                aliases = hints?.titles.orEmpty(),
                references = ids.distinct().map { WorkReference(WorkProvider.ANILIST, it.toString(), WorkKind.ANIME) },
                malId = hints?.malId ?: state.trackItems.firstOrNull { it.tracker.id == 1L }?.track?.remoteId,
            ),
            anime.memo["nyanime.work.v1"] as? JsonObject,
        )
    }
    return rememberWorkInfo(identity, anime.source, manga = false)
}

@Composable
internal fun rememberMangaWorkInfo(state: MangaScreenModel.State.Success): WorkInfoState {
    val manga = state.manga
    val homes = remember { Injekt.get<MangaHomeService>() }
    val homeRevision by homes.identityChanges.collectAsState()
    val identity =
        remember(
            manga.source,
            manga.url,
            manga.title,
            state.trackedAniListId,
            state.trackedMalId,
            state.sourceWorkIdentity,
            homeRevision,
        ) {
            val cachedIds = homes.withAlternatives(MangaHomeItem(manga)).presentation?.catalogIds.orEmpty()
            val ids = listOfNotNull(state.trackedAniListId, cachedIds["anilist"])
            val base = state.sourceWorkIdentity ?: WorkIdentity(
                WorkInfoPreferences.localKey(true, manga.source, manga.url),
                manga.title,
                WorkKind.MANGA,
            )
            base.copy(
                localKey = WorkInfoPreferences.localKey(true, manga.source, manga.url),
                title = manga.title,
                references = (
                    base.references +
                        ids.map { WorkReference(WorkProvider.ANILIST, it.toString(), WorkKind.MANGA) }
                    ).distinct(),
                malId = base.malId ?: state.trackedMalId ?: cachedIds["myanimelist"],
            )
        }
    return rememberWorkInfo(identity, manga.source, manga = true)
}

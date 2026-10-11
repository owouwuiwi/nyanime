package eu.kanade.tachiyomi.data.releases

import eu.kanade.tachiyomi.data.track.SourceTrackingHints
import eu.kanade.tachiyomi.data.track.TrackerManager
import tachiyomi.domain.discovery.homePresentation
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.track.anime.model.AnimeTrack

/** Saved tracker bindings take precedence over unverified extension hints. */
internal data class AiringCatalogReference(val anilistId: Long?, val malId: Long?, val expectedMalId: Long? = malId) {
    val hasId: Boolean get() = anilistId != null || malId != null

    companion object {
        fun from(anime: Anime, tracks: List<AnimeTrack>): AiringCatalogReference {
            val hints = SourceTrackingHints.from(anime)
            val ids = anime.homePresentation?.catalogIds.orEmpty()
            return choose(
                hints?.anilistId ?: ids["anilist"],
                hints?.malId ?: ids["myanimelist"],
                tracks.firstOrNull { it.trackerId == TrackerManager.ANILIST }?.remoteId,
                tracks.firstOrNull { it.trackerId == 1L }?.remoteId,
            )
        }

        fun choose(
            hintAnilist: Long?,
            hintMal: Long?,
            trackedAnilist: Long?,
            trackedMal: Long?,
        ): AiringCatalogReference {
            val anilist = trackedAnilist?.takeIf { it > 0 }
            val mal = trackedMal?.takeIf { it > 0 }
            return when {
                anilist != null -> AiringCatalogReference(
                    anilist,
                    mal ?: hintMal?.takeIf {
                        it > 0
                    },
                    expectedMalId = mal,
                )
                mal != null -> AiringCatalogReference(null, mal)
                else -> AiringCatalogReference(hintAnilist?.takeIf { it > 0 }, hintMal?.takeIf { it > 0 })
            }
        }
    }
}

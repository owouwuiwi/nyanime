package eu.kanade.domain.entries.anime.interactor

import tachiyomi.core.common.util.lang.toLong
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.model.AnimeUpdate
import tachiyomi.domain.entries.anime.repository.AnimeRepository
import uy.kohesive.injekt.api.get
import kotlin.math.pow

class SetAnimeViewerFlags(
    private val animeRepository: AnimeRepository,
) {

    suspend fun awaitSetSkipIntroLength(id: Long, flag: Long) {
        val anime = animeRepository.getAnimeById(id)
        animeRepository.updateAnime(
            AnimeUpdate(
                id = id,
                viewerFlags = anime.viewerFlags
                    .setFlag(flag, Anime.ANIME_INTRO_MASK)
                    // Disable skip intro button if length is set to 0
                    .setFlag((flag == 0L).toLong().addHexZeros(14), Anime.ANIME_INTRO_DISABLE_MASK),
            ),
        )
    }

    suspend fun awaitSetNextEpisodeAiring(id: Long, flags: Pair<Int, Long>) {
        val mask = Anime.ANIME_AIRING_EPISODE_MASK or Anime.ANIME_AIRING_TIME_MASK
        val value = (flags.first.toLong() shl 8) or (flags.second shl 24)
        uy.kohesive.injekt.Injekt.get<tachiyomi.data.handlers.anime.AnimeDatabaseHandler>().await {
            airingQueries.setNextAiring(mask, value and mask, id)
        }
    }

    private fun Long.setFlag(flag: Long, mask: Long): Long {
        return this and mask.inv() or (flag and mask)
    }

    private fun Long.addHexZeros(zeros: Int): Long {
        val hex = 16.0
        return this.times(hex.pow(zeros)).toLong()
    }
}

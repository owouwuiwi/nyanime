package eu.kanade.presentation.workinfo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import eu.kanade.tachiyomi.R
import java.util.Locale

@Composable
internal fun workLabel(value: String): String = when (value.uppercase(Locale.ROOT)) {
    "DIRECTOR", "DIRECTION" -> stringResource(R.string.work_info_director)
    "SCREENPLAY", "SCREENWRITER" -> stringResource(R.string.work_info_screenplay)
    "STORY & ART" -> stringResource(R.string.work_info_story_art)
    "STORY", "ORIGINAL STORY" -> stringResource(R.string.work_info_story_author)
    "ART" -> stringResource(R.string.work_info_artist)
    "CREATOR", "ORIGINAL CREATOR" -> stringResource(R.string.work_info_creator)
    "CHARACTER DESIGN", "ORIGINAL CHARACTER DESIGN" -> stringResource(R.string.work_info_character_design)
    "SERIES COMPOSITION" -> stringResource(R.string.work_info_composition)
    "MUSIC" -> stringResource(R.string.work_info_music)
    "PRODUCER" -> stringResource(R.string.work_info_producer)
    "EXECUTIVE PRODUCER" -> stringResource(R.string.work_info_executive_producer)
    "CAST" -> stringResource(R.string.work_info_actor)
    "VOICE" -> stringResource(R.string.work_info_voice)
    "MAIN" -> stringResource(R.string.work_info_main)
    "SUPPORTING" -> stringResource(R.string.work_info_supporting)
    "PREQUEL" -> stringResource(R.string.work_info_prequel)
    "SEQUEL" -> stringResource(R.string.work_info_sequel)
    "ADAPTATION" -> stringResource(R.string.work_info_adaptation)
    "PARENT" -> stringResource(R.string.work_info_parent)
    "SIDE_STORY" -> stringResource(R.string.work_info_side)
    "ALTERNATIVE", "ALTERNATIVE_VERSION" -> stringResource(R.string.work_info_alternative)
    "RELATED", "OTHER" -> stringResource(R.string.work_info_related)
    "ANIME" -> stringResource(R.string.work_info_anime)
    "MANGA" -> stringResource(R.string.work_info_manga)
    "MOVIE", "FILM" -> stringResource(R.string.work_info_film)
    "TV", "SERIES" -> stringResource(R.string.work_info_series)
    "TV_SHORT" -> stringResource(R.string.work_info_short)
    "SPECIAL" -> stringResource(R.string.work_info_special)
    "NOVEL" -> stringResource(R.string.work_info_novel)
    "ONE_SHOT" -> stringResource(R.string.work_info_oneshot)
    "RELEASING", "RUNNING" -> stringResource(R.string.work_info_ongoing)
    "FINISHED", "ENDED" -> stringResource(R.string.work_info_finished)
    "NOT_YET_RELEASED", "IN DEVELOPMENT" -> stringResource(R.string.work_info_upcoming)
    "CANCELLED" -> stringResource(R.string.work_info_cancelled)
    "HIATUS" -> stringResource(R.string.work_info_paused)
    else -> value.replace('_', ' ')
}

@Composable
internal fun workLanguage(value: String): String {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ENGLISH
    return remember(value, locale) {
        val code = value.takeIf { it.length == 2 } ?: languageCodes[value.lowercase(Locale.ROOT)]
        code?.let { Locale(it).getDisplayLanguage(locale) } ?: value
    }
}

private val languageCodes by lazy {
    Locale.getISOLanguages().associateBy { Locale(it).getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT) }
}

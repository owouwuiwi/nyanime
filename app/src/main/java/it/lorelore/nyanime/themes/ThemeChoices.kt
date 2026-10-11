package it.lorelore.nyanime.themes

import eu.kanade.tachiyomi.data.workinfo.WorkInfoPreferences
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.PreferenceStore

/** Ordinary portable preferences enter the existing settings backup; the public cache does not. */
class ThemeChoices(private val store: PreferenceStore, private val json: Json) {
    fun selected(identity: ThemeIdentity, song: AnimeTheme, kind: ThemeVideoKind): ThemeVideo? =
        store.getString(key(identity, song, kind)).get().takeIf(String::isNotEmpty)?.let {
            runCatching { json.decodeFromString<ThemeVideo>(it) }.getOrNull()
                ?.takeIf { video -> ThemeVideoIds.valid(video.id) && video.kind == kind }
        }

    fun select(identity: ThemeIdentity, song: AnimeTheme, kind: ThemeVideoKind, video: ThemeVideo) {
        require(ThemeVideoIds.valid(video.id) && kind != ThemeVideoKind.UNKNOWN)
        store.getString(
            key(identity, song, kind),
        ).set(json.encodeToString(video.copy(kind = kind, origin = ThemeOrigin.USER)))
    }

    private fun key(identity: ThemeIdentity, song: AnimeTheme, kind: ThemeVideoKind): String =
        "theme_video_choice_v1_${WorkInfoPreferences.digest("${identity.choiceKey}:${song.key}:$kind")}"
}

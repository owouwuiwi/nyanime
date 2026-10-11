package eu.kanade.tachiyomi.data.episodeguide

import eu.kanade.tachiyomi.data.workinfo.WorkInfoPreferences
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.entries.anime.model.Anime

/** Portable ordinary preferences are included by the existing full settings backup. */
class EpisodeGuidePreferences(private val store: PreferenceStore, private val json: Json) {
    fun enabled() = store.getBoolean("episode_guides_enabled", true)
    fun skip() = store.getBoolean("episode_guides_skip", false)
    fun skipOverride(anime: Anime) = store.getInt(key(anime, "skip"), -1)
    fun hide(anime: Anime) = store.getBoolean(key(anime, "hide"), false)
    fun shouldSkip(anime: Anime) = enabled().get() &&
        when (skipOverride(anime).get()) {
            1 -> true
            0 -> false
            else -> skip().get()
        }
    fun binding(anime: Anime): GuideBinding? = store.getString(key(anime, "binding")).get()
        .takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString<GuideBinding>(it) }.getOrNull() }
    fun associate(anime: Anime, binding: GuideBinding) = store.getString(key(anime, "binding"))
        .set(json.encodeToString(binding))
    fun clearBinding(anime: Anime) = store.getString(key(anime, "binding")).delete()
    fun override(anime: Anime, episodeUrl: String): Boolean? = when (
        store.getInt(key(anime, "override:" + WorkInfoPreferences.digest(episodeUrl)), -1).get()
    ) {
        1 -> true
        0 -> false
        else -> null
    }
    fun setOverride(anime: Anime, episodeUrl: String, filler: Boolean?) =
        store.getInt(key(anime, "override:" + WorkInfoPreferences.digest(episodeUrl)), -1)
            .set(filler?.let { if (it) 1 else 0 } ?: -1)

    private fun key(anime: Anime, suffix: String) = "episode_guides_v1_" +
        WorkInfoPreferences.digest(WorkInfoPreferences.localKey(false, anime.source, anime.url)) +
        "_" +
        suffix
}

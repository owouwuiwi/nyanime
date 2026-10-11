package eu.kanade.tachiyomi.data.discovery

import android.content.Context
import tachiyomi.domain.discovery.homePresentation
import tachiyomi.domain.entries.anime.model.Anime

/** The preference is keyed by a public work identity, never by a site's URL or parser detail. */
object SourceHomeSourceChoice {
    private fun keys(anime: Anime): List<String> {
        val presentation = anime.homePresentation ?: return emptyList()
        val publicIds = presentation.catalogIds.toSortedMap().map { (catalogue, id) -> "$catalogue:$id" }
        if (publicIds.isNotEmpty()) return publicIds
        val choices = presentation.choices
        if (choices.size < 2) return emptyList()
        return listOf("local:" + choices.map { "${it.sourceId}:${it.animeId}" }.sorted().joinToString("|"))
    }

    fun preferredAnimeId(context: Context, anime: Anime): Long {
        val choices = anime.homePresentation?.choices.orEmpty()
        val preferences = context.getSharedPreferences("source_home_choices", Context.MODE_PRIVATE)
        val source = keys(anime).firstNotNullOfOrNull { key ->
            preferences.getLong(key, -1).takeIf { it >= 0 }
        } ?: return anime.id
        return choices.firstOrNull { it.sourceId == source }?.animeId ?: anime.id
    }

    fun episodeTarget(anime: Anime, chosenAnimeId: Long): String? = anime.homePresentation?.choices.orEmpty()
        .firstOrNull { it.animeId == chosenAnimeId }?.episodeTarget
        ?: anime.homePresentation?.episodeTarget?.takeIf { chosenAnimeId == anime.id }

    fun remember(context: Context, anime: Anime, chosenAnimeId: Long) {
        val keys = keys(anime)
        if (keys.isEmpty()) return
        val source = anime.homePresentation?.choices.orEmpty()
            .firstOrNull { it.animeId == chosenAnimeId }?.sourceId ?: return
        context.getSharedPreferences("source_home_choices", Context.MODE_PRIVATE)
            .edit().apply {
                keys.forEach { putLong(it, source) }
            }.apply()
    }
}

package eu.kanade.tachiyomi.ui.privacy

import java.text.Normalizer
import java.util.Locale

/** Explicit content labels only. Titles, source names, URLs and artwork are never guessed. */
object NsfwContentPolicy {
    private val explicitLabels = setOf("nsfw", "adult", "18+", "hentai", "erotica", "pornographic")

    fun isNsfw(sourceFlag: Boolean, labels: List<String>?): Boolean = sourceFlag ||
        labels.orEmpty().any {
            Normalizer.normalize(it, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT) in explicitLabels
        }
}

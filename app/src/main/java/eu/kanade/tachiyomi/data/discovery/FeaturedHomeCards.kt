package eu.kanade.tachiyomi.data.discovery

import tachiyomi.data.discovery.mergeHomeCards
import tachiyomi.domain.discovery.homeItemKey
import tachiyomi.domain.discovery.homeTitleVariantKey
import tachiyomi.domain.entries.anime.model.Anime

/** Featured covers represent works; the original episode feed remains untouched. */
internal object FeaturedHomeCards {
    fun items(entries: List<Anime>): List<Anime> {
        val seen = HashSet<Triple<Long, String, String>>()
        val works = entries.map(SourceHomeMetadata::enrich).filter { card ->
            card.url.isBlank() || seen.add(Triple(card.source, card.url, card.homeTitleVariantKey))
        }
        return mergeHomeCards(
            works,
            mergeSourceVariants = true,
            requireVerifiedIdentity = true,
            preserveTitleVariants = true,
        )
    }

    fun key(card: Anime): String {
        val variant = card.homeTitleVariantKey
        val work = if (card.url.isBlank()) card.homeItemKey else "${card.source}:${card.url.length}:${card.url}"
        return "$work:${variant.length}:$variant"
    }
}

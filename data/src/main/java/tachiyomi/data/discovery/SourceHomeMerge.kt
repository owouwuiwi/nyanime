package tachiyomi.data.discovery

import tachiyomi.domain.discovery.SourceHomeChoice
import tachiyomi.domain.discovery.SourceHomePresentation
import tachiyomi.domain.discovery.homeItemKey
import tachiyomi.domain.discovery.homePresentation
import tachiyomi.domain.entries.anime.model.Anime
import java.text.Normalizer
import java.util.Locale

/** Merge public cards, including cards already merged on an earlier catalogue page. */
fun mergeHomeCards(cards: List<Anime>, mergeSourceVariants: Boolean = false): List<Anime> {
    val groups = mutableListOf<MutableList<Anime>>()
    cards.distinctBy { it.homeItemKey }.forEach { card ->
        val group = groups.firstOrNull { members ->
            members.none { member ->
                catalogueConflict(member, card) ||
                    (
                        memberSources(member).any { it in memberSources(card) } &&
                            (!mergeSourceVariants || !catalogueMatch(member, card))
                        )
            } &&
                members.any { sameWork(it, card, mergeSourceVariants) }
        }
        if (group == null) groups.add(mutableListOf(card)) else group.add(card)
    }
    return groups.map { members ->
        val first = members.first()
        if (members.size == 1) return@map first
        val choices = members.flatMap { member ->
            member.homePresentation?.choices?.takeIf { it.isNotEmpty() }
                ?: listOf(
                    SourceHomeChoice(member.id, member.source, member.title, member.homePresentation?.episodeTarget),
                )
        }
        val ids = members.flatMap { it.homePresentation?.catalogIds.orEmpty().entries }
            .associate { it.key to it.value }
        val years = members.mapNotNull { it.homePresentation?.releaseYear }.distinct()
        val aliases = members.flatMap { member -> listOf(member.title) + member.homePresentation?.aliases.orEmpty() }
            .distinct()
        val presentation = (first.homePresentation ?: SourceHomePresentation()).copy(
            catalogIds = ids,
            aliases = aliases,
            releaseYear = years.singleOrNull(),
            choices = choices,
        )
        first.copy(memo = presentation.attachTo(first.memo))
    }
}

private fun memberSources(card: Anime): Set<Long> = card.homePresentation?.choices
    ?.map(SourceHomeChoice::sourceId)?.toSet()?.takeIf { it.isNotEmpty() } ?: setOf(card.source)

private fun sameWork(left: Anime, right: Anime, mergeSourceVariants: Boolean): Boolean {
    if (left.source == right.source && !mergeSourceVariants) return false
    if (catalogueConflict(left, right)) return false
    val a = left.homePresentation
    val b = right.homePresentation
    val idsA = a?.catalogIds.orEmpty()
    val idsB = b?.catalogIds.orEmpty()
    val sharedKinds = idsA.keys intersect idsB.keys
    if (sharedKinds.any { idsA[it] == idsB[it] }) return true
    // Editions inside a provider need a verified common ID; title/year alone is insufficient.
    if (left.source == right.source) return false
    if (a?.releaseYear != null && b?.releaseYear != null && a.releaseYear != b.releaseYear) return false
    // Without catalogue IDs, require another independent signal; identical titles alone can name different works.
    if (a?.releaseYear == null || b?.releaseYear == null) return false
    val titlesA = (listOf(left.title) + a?.aliases.orEmpty()).map(::normalTitle).filter(String::isNotEmpty).toSet()
    val titlesB = (listOf(right.title) + b?.aliases.orEmpty()).map(::normalTitle).filter(String::isNotEmpty).toSet()
    return titlesA.any { it in titlesB }
}

private fun catalogueMatch(left: Anime, right: Anime): Boolean {
    val a = left.homePresentation?.catalogIds.orEmpty()
    val b = right.homePresentation?.catalogIds.orEmpty()
    val shared = a.keys intersect b.keys
    return shared.isNotEmpty() && shared.all { a[it] == b[it] }
}

private fun catalogueConflict(left: Anime, right: Anime): Boolean {
    val leftIds = left.homePresentation?.catalogIds.orEmpty()
    val rightIds = right.homePresentation?.catalogIds.orEmpty()
    return (leftIds.keys intersect rightIds.keys).any { leftIds[it] != rightIds[it] }
}

private fun normalTitle(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
    .replace(Regex("\\p{M}+"), "")
    .lowercase(Locale.ROOT)
    .replace(Regex("(?:\\s*[(\\[]?(?:sub[ -]?ita|dub[ -]?ita|ita)[)\\]]?)+$"), "")
    .replace(Regex("[^a-z0-9]+"), " ")
    .trim()
    .replace(Regex("\\s+"), " ")

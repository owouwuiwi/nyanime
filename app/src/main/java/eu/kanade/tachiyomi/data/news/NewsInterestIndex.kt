package eu.kanade.tachiyomi.data.news

import kotlinx.serialization.Serializable
import nyanime.news.api.NewsCatalogId
import nyanime.news.api.NewsMedium
import tachiyomi.domain.search.TitleNormalizer

/** Catalog-grounded names are relevance evidence, never new tracker or extension identities. */
@Serializable
data class NewsCatalogWork(
    val ids: Set<NewsCatalogId>,
    val title: String,
    val names: List<String>,
    val medium: NewsMedium,
) {
    @kotlinx.serialization.Transient
    val normalizedNames = names.asSequence().filter { it.length in 3..256 }
        .map(TitleNormalizer::words).filter { it.length >= 3 }.distinct().toList()
}

enum class NewsMatchKind { ID, TOPIC, HEADLINE, LOCAL_TITLE }
data class NewsInterestMatch(val title: String, val kind: NewsMatchKind, val reliable: Boolean)

/** Built once for a feed pass, rather than normalizing a complete library for every card. */
class NewsInterestIndex(
    private val snapshot: NewsSnapshot,
    private val library: NewsPersonalLibrary,
    personal: Set<NewsCatalogId> = NewsRules.personalIds(
        snapshot,
        library.titles.filter { it.key !in snapshot.excludedTitles }.flatMap { it.ids }.toSet(),
    ),
) {
    private val excluded = NewsRules.excludedIds(snapshot)
    private val wanted = personal - excluded
    private data class Candidate(val work: NewsCatalogWork, val local: NewsPersonalTitle? = null)
    private val works = snapshot.works.values.distinctBy { it.ids }
    private val workById = works.flatMap { work -> work.ids.map { it to work } }.toMap()
    private val local = library.titles.filter { title ->
        title.ids.isEmpty() || title.ids.none { it in workById }
    }

    // Include unselected catalog works in ambiguity checks. Excluding a title must not
    // turn a previously ambiguous name into a false match for another work.
    private val candidates = works.map { Candidate(it) } +
        local.map {
            Candidate(NewsCatalogWork(it.ids, it.title, listOf(it.title) + it.aliases, it.medium), it)
        }
    private val byName = candidates.flatMap { candidate ->
        candidate.work.normalizedNames.map { it to candidate }
    }.groupBy({ it.first }, { it.second })
    private val headlineNames = byName.keys.filter { name ->
        name.length >= 8 && name.count { it == ' ' } >= 1 && byName.getValue(name).any(::selected)
    }.sortedByDescending { it.length }.groupBy { it.substringBefore(' ') }

    private fun selected(candidate: Candidate): Boolean = candidate.local?.let {
        it.key !in snapshot.excludedTitles && it.ids.none { id -> id in excluded }
    } ?: candidate.work.ids.any { it in wanted }
    private fun accepts(item: StoredNews, candidate: Candidate) =
        item.article.media.isEmpty() || candidate.work.medium in item.article.media

    private fun unique(name: String, item: StoredNews): Candidate? {
        val matches = byName[name].orEmpty().filter { accepts(item, it) }
            .distinctBy { it.work.ids.takeIf { ids -> ids.isNotEmpty() } ?: it.local?.key ?: it.work.title }
        if (matches.isEmpty() || matches.any { !selected(it) }) return null
        // If every possible work is followed, the topic is relevant even without choosing an adaptation.
        // Local-only evidence keeps the weaker classification for the whole ambiguous group.
        return matches.firstOrNull { it.local != null } ?: matches.first()
    }

    fun match(item: StoredNews): NewsInterestMatch? {
        val direct = NewsRules.identities(item, snapshot).intersect(wanted)
        if (direct.isNotEmpty()) {
            val title = direct.firstNotNullOfOrNull { workById[it]?.title }
                ?: library.titles.firstOrNull { title -> title.ids.any { it in direct } }?.title
                ?: item.article.topics.firstOrNull { topic -> topic.catalogIds.any { it in direct } }?.title.orEmpty()
            return NewsInterestMatch(title, NewsMatchKind.ID, true)
        }
        for (topic in item.article.topics) {
            // Explicit identities and user choices have priority over names.
            if (topic.catalogIds.isNotEmpty() ||
                NewsRules.topicKey(item.source, topic.id) in snapshot.mappings
            ) {
                continue
            }
            val candidate = unique(TitleNormalizer.words(topic.title), item) ?: continue
            return NewsInterestMatch(
                candidate.work.title,
                if (candidate.local == null) NewsMatchKind.TOPIC else NewsMatchKind.LOCAL_TITLE,
                candidate.local == null,
            )
        }
        if (item.article.topics.any {
                it.catalogIds.isNotEmpty() ||
                    NewsRules.topicKey(item.source, it.id) in snapshot.mappings
            }
        ) {
            return null
        }
        val headline = " ${TitleNormalizer.words(item.article.title)} "
        // Index by first word: a headline examines only its candidate names, not the entire catalog cache.
        val names = headline.trim().split(' ').distinct().flatMap { headlineNames[it].orEmpty() }
        for (name in names) {
            val candidate = unique(name, item) ?: continue
            val needle = " $name "
            var offset = headline.indexOf(needle)
            while (offset >= 0) {
                val after = headline.substring(offset + needle.length).trimStart()
                if (!NUMBERED_EDITION.containsMatchIn(after)) {
                    return NewsInterestMatch(candidate.work.title, NewsMatchKind.HEADLINE, false)
                }
                offset = headline.indexOf(needle, offset + 1)
            }
        }
        return null
    }

    private companion object {
        val NUMBERED_EDITION = Regex("^(?:(?:season|stagione|part|parte) )?\\d+(?: |$)")
    }
}

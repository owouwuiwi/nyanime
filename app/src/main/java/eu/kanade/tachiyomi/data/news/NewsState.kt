package eu.kanade.tachiyomi.data.news

import kotlinx.serialization.Serializable
import nyanime.news.api.NewsArticle
import nyanime.news.api.NewsBlockKind
import nyanime.news.api.NewsCatalogId
import java.net.URI
import java.security.MessageDigest

@Serializable
enum class NewsAlerts { OFF, PERSONAL, ALL }

@Serializable
data class NewsSourceSettings(
    val enabled: Boolean = false,
    val signers: Set<String> = emptySet(),
    val distribution: String? = null,
    val alerts: NewsAlerts = NewsAlerts.OFF,
)

@Serializable
data class StoredNews(
    val source: String,
    val article: NewsArticle,
    val acquiredAt: Long,
    val read: Boolean = false,
    val saved: Boolean = false,
    val position: Int = 0,
    val offset: Int = 0,
) {
    @kotlinx.serialization.Transient
    val key: String = NewsRules.key(source, article.id)
}

@Serializable
data class NewsCheck(
    val checkedAt: Long = 0,
    val baseline: Long = 0,
    val retryAt: Long = 0,
    val failures: Int = 0,
    val error: Boolean = false,
    val nextCursor: String? = null,
)

@Serializable
data class NewsSnapshot(
    val version: Int = 1,
    val sources: Map<String, NewsSourceSettings> = emptyMap(),
    val articles: Map<String, StoredNews> = emptyMap(),
    val checks: Map<String, NewsCheck> = emptyMap(),
    val mappings: Map<String, Set<NewsCatalogId>> = emptyMap(),
    val excluded: Set<NewsCatalogId> = emptySet(),
    val receipts: Set<String> = emptySet(),
    val pending: Set<String> = emptySet(),
    val pendingClassification: Set<String> = emptySet(),
    val relations: Map<String, Set<NewsCatalogId>> = emptyMap(),
    val relationsCheckedAt: Long = 0,
    val relationChecks: Map<String, Long> = emptyMap(),
    val works: Map<String, NewsCatalogWork> = emptyMap(),
    val catalogChecks: Map<String, Long> = emptyMap(),
    val metadataChecks: Map<String, Long> = emptyMap(),
    val excludedTitles: Set<String> = emptySet(),
    val textScale: Float = 1f,
)

/** Pure policies shared by foreground refresh, the worker and restore. */
object NewsRules {
    fun catalogKey(id: NewsCatalogId) = "${id.provider}:${id.medium}:${id.value}"
    fun excludedIds(snapshot: NewsSnapshot): Set<NewsCatalogId> = snapshot.excluded +
        snapshot.works.values.filter { work -> work.ids.any { it in snapshot.excluded } }.flatMap { it.ids }

    fun personalIds(snapshot: NewsSnapshot, roots: Set<NewsCatalogId>): Set<NewsCatalogId> {
        val excluded = excludedIds(snapshot)
        val included = roots - excluded
        val equivalents = snapshot.works.values.filter { work -> work.ids.any { it in included } }.flatMap { it.ids }
        val selected = included + equivalents
        return (selected + selected.flatMap { snapshot.relations[catalogKey(it)].orEmpty() }) - excluded
    }
    fun key(source: String, id: String): String = digest("$source\u0000$id")
    fun topicKey(source: String, topic: String): String = key(source, topic)
    private fun digest(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    fun webUrl(url: String?): Boolean = runCatching {
        val uri = URI(url ?: return false)
        uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null && url.length <= 8192
    }.getOrDefault(false)

    fun validate(article: NewsArticle): NewsArticle {
        require(article.id.isNotBlank() && article.id.length <= 2048)
        require(article.title.isNotBlank() && article.title.length <= 1024)
        require(webUrl(article.url))
        require(article.topics.size <= 100 && article.blocks.size <= 500)
        require(article.blocks.sumOf { it.text.length + (it.caption?.length ?: 0) } <= 500_000)
        return article.copy(
            imageUrl = article.imageUrl?.takeIf(::webUrl),
            publishedAt = article.publishedAt?.takeIf { it > 0 },
            blocks = article.blocks.filter {
                it.kind !in setOf(NewsBlockKind.IMAGE, NewsBlockKind.MEDIA) || webUrl(it.url)
            },
        )
    }

    fun identities(item: StoredNews, snapshot: NewsSnapshot): Set<NewsCatalogId> = item.article.topics
        .flatMap { it.catalogIds + snapshot.mappings[topicKey(item.source, it.id)].orEmpty() }.toSet()

    fun personal(item: StoredNews, snapshot: NewsSnapshot, library: Set<NewsCatalogId>): Boolean =
        NewsInterestIndex(snapshot, NewsPersonalLibrary(), library).match(item) != null

    /** First successful fetch is a baseline, never a burst of historical notifications. */
    fun shouldNotify(
        item: StoredNews,
        before: NewsSnapshot,
        personal: Set<NewsCatalogId>,
        now: Long,
        index: NewsInterestIndex = NewsInterestIndex(before, NewsPersonalLibrary(), personal),
    ): Boolean {
        if (!eligibleForAlert(item, before, now)) return false
        val source = before.sources[item.source] ?: return false
        return source.alerts == NewsAlerts.ALL || index.match(item)?.reliable == true
    }

    fun eligibleForAlert(item: StoredNews, before: NewsSnapshot, now: Long): Boolean {
        val source = before.sources[item.source] ?: return false
        val baseline = before.checks[item.source]?.baseline?.takeIf { it > 0 } ?: return false
        val published = item.article.publishedAt ?: return false
        if (!source.enabled || source.alerts == NewsAlerts.OFF || item.key in before.receipts) return false
        if (item.key in before.articles ||
            before.articles.values.any { it.article.url == item.article.url }
        ) {
            return false
        }
        if (published < baseline || published > now + 300_000 || now - published > 7 * 86_400_000L) return false
        return true
    }

    /** Only newly acquired alert candidates can be promoted after catalog/article enrichment. */
    fun classifyPending(snapshot: NewsSnapshot, library: NewsPersonalLibrary, now: Long): NewsSnapshot {
        val index = NewsInterestIndex(snapshot, library)
        val valid = snapshot.pendingClassification.mapNotNull(snapshot.articles::get).filter { item ->
            val settings = snapshot.sources[item.source]
            val baseline = snapshot.checks[item.source]?.baseline?.takeIf { it > 0 }
            val published = item.article.publishedAt
            settings?.enabled == true &&
                settings.alerts == NewsAlerts.PERSONAL &&
                !item.read &&
                item.key !in snapshot.receipts &&
                baseline != null &&
                published != null &&
                published >= baseline &&
                published <= now + 300_000 &&
                now - item.acquiredAt <= 86_400_000 &&
                now - published <= 7 * 86_400_000L
        }
        val matched = valid.filter { index.match(it)?.reliable == true }.map { it.key }.toSet()
        return snapshot.copy(
            pending = snapshot.pending + matched,
            pendingClassification = valid.map { it.key }.toSet() - matched,
        )
    }

    fun merge(before: NewsSnapshot, source: String, entries: List<NewsArticle>, now: Long): NewsSnapshot {
        val articles = before.articles.toMutableMap()
        entries.forEach { incoming ->
            val valid = validate(incoming)
            val key = key(source, valid.id)
            val old = articles[key] ?: articles.values.firstOrNull {
                it.source == source && it.article.url == valid.url
            }
            val article = if (old != null && valid.blocks.isEmpty()) {
                valid.copy(
                    blocks = old.article.blocks,
                    fullText = old.article.fullText,
                    attribution = old.article.attribution,
                    imageUrl = valid.imageUrl ?: old.article.imageUrl,
                    author = valid.author ?: old.article.author,
                    topics = (valid.topics + old.article.topics).groupBy { it.id }.values.map { versions ->
                        versions.first().copy(catalogIds = versions.flatMap { it.catalogIds }.toSet())
                    }.take(100),
                )
            } else {
                valid
            }
            if (old != null && old.key != key) articles.remove(old.key)
            articles[key] = old?.copy(article = article) ?: StoredNews(source, article, now)
        }
        // Read markers outlive the ordinary cache through receipts; saved text is never evicted.
        val recent = articles.values.filterNot { it.saved }.sortedByDescending { it.acquiredAt }.take(1000)
        var cachedCharacters = 0
        var cachedBodies = 0
        val retained = articles.values.filter { it.saved } +
            recent.map { item ->
                val size = item.article.blocks.sumOf { it.text.length }
                if (item.article.blocks.isEmpty()) {
                    item
                } else if (cachedBodies < 40 &&
                    cachedCharacters + size <= 4_000_000
                ) {
                    cachedBodies++
                    cachedCharacters += size
                    item
                } else {
                    item.copy(article = item.article.copy(blocks = emptyList()))
                }
            }
        val kept = retained.associateBy { it.key }
        return before.copy(articles = kept, metadataChecks = before.metadataChecks.filterKeys { it in kept })
    }
}

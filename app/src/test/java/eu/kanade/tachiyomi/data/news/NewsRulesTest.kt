package eu.kanade.tachiyomi.data.news

import nyanime.news.api.NewsArticle
import nyanime.news.api.NewsBlock
import nyanime.news.api.NewsBlockKind
import nyanime.news.api.NewsCatalogId
import nyanime.news.api.NewsMedium
import nyanime.news.api.NewsTopic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NewsRulesTest {
    private val id = NewsCatalogId("catalog", "123", NewsMedium.ANIME)
    private fun article(
        key: String = "1",
    ) = NewsArticle(key, "https://publisher.invalid/article/$key", "A new story", "Test publisher", "en", 20_000)
    private val enabled = NewsSourceSettings(enabled = true, alerts = NewsAlerts.ALL)
    private fun state() = NewsSnapshot(
        sources = mapOf("example" to enabled),
        checks = mapOf("example" to NewsCheck(baseline = 10_000)),
    )

    @Test fun `first fetch and undated articles never notify`() {
        val item = StoredNews("example", article(), 25_000)
        assertFalse(NewsRules.shouldNotify(item, state().copy(checks = emptyMap()), emptySet(), 30_000))
        val undated = item.copy(article = article().copy(publishedAt = null))
        assertFalse(NewsRules.shouldNotify(undated, state(), emptySet(), 30_000))
        assertEquals(null, NewsRules.validate(article().copy(publishedAt = null)).publishedAt)
    }

    @Test fun `an article revision is not another delivery`() {
        val item = StoredNews("example", article(), 25_000)
        assertTrue(NewsRules.shouldNotify(item, state(), emptySet(), 30_000))
        assertFalse(NewsRules.shouldNotify(item, state().copy(receipts = setOf(item.key)), emptySet(), 30_000))
        assertFalse(
            NewsRules.shouldNotify(
                item,
                NewsRules.merge(state(), "example", listOf(article()), 25_000),
                emptySet(),
                30_000,
            ),
        )
    }

    @Test fun `similar titles and different media do not become identities`() {
        val item = StoredNews("example", article().copy(topics = listOf(NewsTopic("topic", "A new story"))), 25_000)
        assertFalse(NewsRules.personal(item, state(), setOf(id)))
        val confirmed = state().copy(mappings = mapOf(NewsRules.topicKey("example", "topic") to setOf(id)))
        assertTrue(NewsRules.personal(item, confirmed, setOf(id)))
        assertFalse(NewsRules.personal(item, confirmed, setOf(id.copy(medium = NewsMedium.MANGA))))
        assertFalse(NewsRules.personal(item, confirmed.copy(excluded = setOf(id)), setOf(id)))
    }

    @Test fun `refresh keeps saved text and reading position`() {
        val content = article().copy(blocks = listOf(NewsBlock(NewsBlockKind.TEXT, "Full article")), fullText = true)
        val item = StoredNews("example", content, 10_000, saved = true, read = true, position = 4)
        val before = state().copy(articles = mapOf(item.key to item))
        val result = NewsRules.merge(
            before,
            "example",
            listOf(article().copy(title = "Updated headline")),
            50_000,
        ).articles.getValue(item.key)
        assertEquals(4, result.position)
        assertTrue(result.saved)
        assertTrue(result.article.fullText)
        assertEquals(content.blocks, result.article.blocks)
        assertEquals(10_000, result.acquiredAt)
    }

    @Test fun `canonical identity merges same source revisions but similar titles remain separate`() {
        val first = NewsRules.merge(state(), "example", listOf(article()), 30_000)
        val second = NewsRules.merge(first, "example", listOf(article("2").copy(url = article().url)), 35_000)
        assertEquals(1, second.articles.size)
        assertEquals(2, NewsRules.merge(second, "example", listOf(article("3")), 35_000).articles.size)
    }

    @Test fun `cache bounds never evict saved articles`() {
        val item = StoredNews("example", article(), 1, saved = true)
        val result = NewsRules.merge(
            state().copy(articles = mapOf(item.key to item)),
            "example",
            (2..1200).map {
                article(it.toString())
            },
            50_000,
        )
        assertEquals(1001, result.articles.size)
        assertTrue(result.articles.getValue(item.key).saved)
    }

    @Test fun `reader rejects non web image locations`() {
        for (url in listOf(
            "file:///data/private",
            "javascript:alert(1)",
            "https://user:secret@publisher.invalid/",
            "content://private/1",
        )) {
            assertFalse(NewsRules.webUrl(url))
        }
        val result = NewsRules.validate(
            article().copy(blocks = listOf(NewsBlock(NewsBlockKind.IMAGE, url = "file:///private"))),
        )
        assertTrue(result.blocks.isEmpty())
    }
}

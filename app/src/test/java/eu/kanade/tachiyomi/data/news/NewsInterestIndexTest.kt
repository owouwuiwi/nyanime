package eu.kanade.tachiyomi.data.news

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import nyanime.news.api.NewsArticle
import nyanime.news.api.NewsCatalogId
import nyanime.news.api.NewsMedium
import nyanime.news.api.NewsTopic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NewsInterestIndexTest {
    private val anime = NewsCatalogId("anilist", "101", NewsMedium.ANIME)
    private val mal = NewsCatalogId("myanimelist", "201", NewsMedium.ANIME)
    private val work =
        NewsCatalogWork(
            setOf(anime, mal),
            "Sky Harbor",
            listOf("Sky Harbor", "Porto del cielo", "Sora no Minato"),
            NewsMedium.ANIME,
        )
    private val library =
        NewsPersonalLibrary(listOf(NewsPersonalTitle("Sky Harbor (dub)", setOf(anime), NewsMedium.ANIME, "local-key")))
    private val state = NewsSnapshot(works = mapOf("work" to work))
    private fun article(
        title: String = "New announcement",
        topic: String? = null,
        ids: Set<NewsCatalogId> = emptySet(),
        medium: NewsMedium? = null,
    ) = StoredNews(
        "example",
        NewsArticle(
            "article",
            "https://publisher.invalid/article",
            title,
            "Test publisher",
            "en",
            20_000,
            topics = topic?.let { listOf(NewsTopic("topic", it, catalogIds = ids)) }.orEmpty(),
            media = medium?.let(::setOf).orEmpty(),
        ),
        25_000,
    )

    @Test fun `publisher tags match catalog translations and punctuation`() {
        val index = NewsInterestIndex(state, library)
        for (name in listOf("PORTO DEL CIELO", "Sora-no-Minato", "Sky: Harbor")) {
            val match = requireNotNull(index.match(article(topic = name)))
            assertTrue(match.reliable)
            assertEquals(NewsMatchKind.TOPIC, match.kind)
            assertEquals(work.title, match.title)
        }
    }

    @Test fun `verified alternate catalog ID matches without a second tracker`() {
        assertTrue(
            NewsInterestIndex(
                state,
                library,
            ).match(article(topic = "Unrelated spelling", ids = setOf(mal)))?.reliable ==
                true,
        )
        assertEquals(setOf(anime, mal), NewsRules.personalIds(state, setOf(anime)))
    }

    @Test fun `ambiguous alias remains ambiguous after excluding the other work`() {
        val otherId = anime.copy(value = "102")
        val other = work.copy(ids = setOf(otherId), title = "Sky Harbor 2")
        val snapshot = state.copy(works = state.works + ("other" to other), excluded = setOf(otherId))
        assertNull(NewsInterestIndex(snapshot, library).match(article(topic = "Sky Harbor")))
    }

    @Test fun `medium distinguishes adaptations when the publisher supplies it`() {
        val mangaId = anime.copy(value = "301", medium = NewsMedium.MANGA)
        val manga = work.copy(ids = setOf(mangaId), medium = NewsMedium.MANGA)
        val snapshot = state.copy(works = state.works + ("manga" to manga))
        assertNull(NewsInterestIndex(snapshot, library).match(article(topic = "Sky Harbor")))
        assertNull(NewsInterestIndex(snapshot, library).match(article(topic = "Sky Harbor", medium = NewsMedium.MANGA)))
        assertTrue(
            NewsInterestIndex(
                snapshot,
                library,
            ).match(article(topic = "Sky Harbor", medium = NewsMedium.ANIME))?.reliable ==
                true,
        )
    }

    @Test fun `verified direct adaptation edges include typed manga IDs`() {
        val mangaId = anime.copy(value = "301", medium = NewsMedium.MANGA)
        val snapshot = state.copy(relations = mapOf(NewsRules.catalogKey(anime) to setOf(mangaId)))
        assertTrue(
            NewsInterestIndex(snapshot, library).match(article(topic = "A manga", ids = setOf(mangaId)))?.reliable ==
                true,
        )
        assertNull(NewsInterestIndex(state, library).match(article(topic = "A manga", ids = setOf(mangaId))))
    }

    @Test fun `a shared topic is relevant when every possible adaptation is followed`() {
        val mangaId = anime.copy(value = "301", medium = NewsMedium.MANGA)
        val manga = work.copy(ids = setOf(mangaId), medium = NewsMedium.MANGA)
        val snapshot = state.copy(
            works = state.works + ("manga" to manga),
            relations = mapOf(NewsRules.catalogKey(anime) to setOf(mangaId)),
        )
        assertTrue(NewsInterestIndex(snapshot, library).match(article(topic = "Sky Harbor"))?.reliable == true)
    }

    @Test fun `excluding either equivalent ID excludes the root and its related works`() {
        val related = anime.copy(value = "302")
        val snapshot = state.copy(
            excluded = setOf(mal),
            relations = mapOf(NewsRules.catalogKey(anime) to setOf(related)),
        )
        assertTrue(NewsRules.personalIds(snapshot, setOf(anime)).isEmpty())
        assertNull(NewsInterestIndex(snapshot, library).match(article(topic = "Sky Harbor")))
    }

    @Test fun `headline mention is visible but never a personal notification`() {
        val item = article(title = "Sky Harbor: a new trailer")
        val match = requireNotNull(NewsInterestIndex(state, library).match(item))
        assertEquals(NewsMatchKind.HEADLINE, match.kind)
        assertFalse(match.reliable)
        val enabled = state.copy(
            sources = mapOf("example" to NewsSourceSettings(true, alerts = NewsAlerts.PERSONAL)),
            checks = mapOf("example" to NewsCheck(baseline = 10_000)),
        )
        assertFalse(NewsRules.shouldNotify(item, enabled, library.ids, 30_000, NewsInterestIndex(enabled, library)))
    }

    @Test fun `headline cannot replace an explicit different work or manual choice`() {
        val wrongId = anime.copy(value = "999")
        assertNull(
            NewsInterestIndex(
                state,
                library,
            ).match(article(title = "Sky Harbor: trailer", topic = "Sky Harbor", ids = setOf(wrongId))),
        )
        val snapshot = state.copy(mappings = mapOf(NewsRules.topicKey("example", "topic") to setOf(wrongId)))
        assertNull(NewsInterestIndex(snapshot, library).match(article(topic = "Sky Harbor")))
    }

    @Test fun `base title never captures a numbered season headline`() {
        val index = NewsInterestIndex(state, library)
        for (title in listOf(
            "Sky Harbor 2 announced",
            "Sky Harbor season 2 announced",
            "Sky Harbor part 3 announced",
            "Sky Harbors announced",
        )) {
            assertNull(index.match(article(title = title)))
        }
        val sequel = work.copy(
            ids = setOf(anime.copy(value = "102")),
            title = "Sky Harbor 2",
            names = listOf("Sky Harbor 2"),
        )
        val selected = NewsPersonalLibrary(listOf(NewsPersonalTitle(sequel.title, sequel.ids, NewsMedium.ANIME)))
        val snapshot = state.copy(works = state.works + ("sequel" to sequel))
        assertEquals(
            sequel.title,
            NewsInterestIndex(snapshot, selected).match(article(title = "Sky Harbor 2: trailer"))?.title,
        )
    }

    @Test fun `single short words only match explicit topics`() {
        val short = work.copy(title = "Harbor", names = listOf("Harbor"))
        val index = NewsInterestIndex(state.copy(works = mapOf("work" to short)), library)
        assertNull(index.match(article(title = "A harbor festival was announced")))
        assertTrue(index.match(article(topic = "Harbor"))?.reliable == true)
    }

    @Test fun `untracked local names are useful without creating identities or notifications`() {
        val local = NewsPersonalLibrary(listOf(NewsPersonalTitle("Moon Garden", emptySet(), NewsMedium.MANGA, "local")))
        val index = NewsInterestIndex(NewsSnapshot(), local)
        assertEquals(NewsMatchKind.LOCAL_TITLE, index.match(article(topic = "Moon Garden"))?.kind)
        assertFalse(requireNotNull(index.match(article(topic = "Moon Garden"))).reliable)
        assertTrue(local.ids.isEmpty())
        assertNull(
            NewsInterestIndex(
                NewsSnapshot(excludedTitles = setOf("local")),
                local,
            ).match(article(topic = "Moon Garden")),
        )
    }

    @Test fun `manual association overrides an ambiguous topic`() {
        val other = work.copy(ids = setOf(anime.copy(value = "102")))
        val snapshot = state.copy(
            works = state.works + ("other" to other),
            mappings = mapOf(NewsRules.topicKey("example", "topic") to setOf(anime)),
        )
        assertEquals(NewsMatchKind.ID, NewsInterestIndex(snapshot, library).match(article(topic = "Sky Harbor"))?.kind)
    }

    @Test fun `local exclusion survives a later automatic tracker binding`() {
        val snapshot = state.copy(excludedTitles = setOf("local-key"))
        assertNull(NewsInterestIndex(snapshot, library).match(article(topic = "Sky Harbor")))
        assertNull(NewsInterestIndex(snapshot, library).match(article(topic = "Sky Harbor", ids = setOf(anime))))
    }

    @Test fun `catalog parser preserves multilingual names and typed equivalent IDs`() {
        val node = Json.parseToJsonElement(
            """{"id":101,"idMal":201,"type":"ANIME","title":{"romaji":"Sora no Minato","english":"Sky Harbor","native":"空の港"},"synonyms":["Porto del cielo",null,"Sky Harbor"]}""",
        ).jsonObject
        val parsed = requireNotNull(NewsRelations.parseWork(node))
        assertEquals(setOf(anime, mal), parsed.ids)
        assertEquals(listOf("Sora no Minato", "Sky Harbor", "空の港", "Porto del cielo"), parsed.names)
        assertNull(NewsRelations.parseWork(Json.parseToJsonElement("""{"id":0,"type":"MANGA"}""").jsonObject))
    }

    @Test fun `older stores decode without new personalization fields`() {
        val snapshot = Json.decodeFromString<NewsSnapshot>("""{"version":1}""")
        assertTrue(snapshot.works.isEmpty())
        assertTrue(snapshot.pendingClassification.isEmpty())
        assertTrue(snapshot.excludedTitles.isEmpty())
    }

    @Test fun `feed revision retains catalog evidence already fetched from the article`() {
        val item = article(topic = "Sky Harbor", ids = setOf(anime))
        val snapshot = state.copy(articles = mapOf(item.key to item))
        val summary = item.article.copy(topics = listOf(NewsTopic("topic", "Sky Harbor")))
        assertEquals(
            setOf(anime),
            NewsRules.merge(
                snapshot,
                "example",
                listOf(summary),
                30_000,
            ).articles.getValue(item.key).article.topics.single().catalogIds,
        )
    }

    private fun pending(item: StoredNews) = state.copy(
        sources = mapOf("example" to NewsSourceSettings(true, alerts = NewsAlerts.PERSONAL)),
        checks = mapOf("example" to NewsCheck(baseline = 10_000)),
        articles = mapOf(item.key to item),
        pendingClassification = setOf(item.key),
    )

    @Test fun `late metadata can notify once without reviving historic articles`() {
        val item = article(topic = "Sky Harbor")
        val before = pending(item).copy(works = emptyMap())
        assertTrue(NewsRules.classifyPending(before, library, 30_000).pending.isEmpty())
        val enriched = before.copy(works = state.works)
        assertEquals(setOf(item.key), NewsRules.classifyPending(enriched, library, 30_000).pending)
        assertTrue(
            NewsRules.classifyPending(
                enriched.copy(pendingClassification = emptySet()),
                library,
                30_000,
            ).pending.isEmpty(),
        )
        assertTrue(NewsRules.classifyPending(enriched.copy(checks = emptyMap()), library, 30_000).pending.isEmpty())
        assertTrue(
            NewsRules.classifyPending(
                enriched.copy(checks = mapOf("example" to NewsCheck(baseline = 30_000))),
                library,
                30_000,
            ).pending.isEmpty(),
        )
    }

    @Test fun `read delivered expired or disabled candidates never become alerts`() {
        val item = article(topic = "Sky Harbor")
        val snapshot = pending(item)
        val blocked = listOf(
            snapshot.copy(articles = mapOf(item.key to item.copy(read = true))),
            snapshot.copy(receipts = setOf(item.key)),
            snapshot.copy(excluded = setOf(anime)),
            snapshot.copy(sources = mapOf("example" to NewsSourceSettings(true, alerts = NewsAlerts.OFF))),
            snapshot.copy(sources = mapOf("example" to NewsSourceSettings(false, alerts = NewsAlerts.PERSONAL))),
        )
        blocked.forEach { assertTrue(NewsRules.classifyPending(it, library, 30_000).pending.isEmpty()) }
        assertTrue(NewsRules.classifyPending(snapshot, library, 2 * 86_400_000L).pending.isEmpty())
    }
}

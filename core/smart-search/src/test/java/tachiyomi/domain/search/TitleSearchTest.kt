package tachiyomi.domain.search

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class TitleSearchTest {
    private val matcher = LexicalTitleMatcher()
    private fun title(name: String, key: String = name, aliases: List<String> = emptyList()) =
        SearchTitle(key, name, SearchMedium.VIDEO, aliases)

    @Test
    fun `punctuation spaces accents missing and swapped letters preserve numeric editions`() {
        listOf("Solar Gate", "solargate", "Slar Gate", "Sloar Gate", "Solar;Gate")
            .forEach { (matcher.score(it, "Solar;Gate") >= 85) shouldBe true }
        matcher.score("Cafe Horizon", "Café Horizon") shouldBe 98
        matcher.score("Solar Gate 2", "Solar Gate 3") shouldBe 0
        matcher.score("Qx", "Qy") shouldBe 0
        matcher.score("Solar Gate", "Solar Gate 2") shouldBe 82
        matcher.score("Synthetc Volume", "Synthetic Volume 12") shouldBe 75
        (matcher.score("Synthetc", "Synthetic Volume") >= 75) shouldBe true
    }

    @Test
    fun `numbers belonging to names survive additional season or part suffixes`() {
        matcher.score("Synthetic Protocol 47", "Synthetic Protocol 48") shouldBe 0
        (matcher.score("Synthetic Protocol 47", "Synthetic Protocol 47 Season 2") >= 75) shouldBe true
        matcher.score("Synthetic Protocol 47 Season 2", "Synthetic Protocol 47 Season 3") shouldBe 0
        val index = SymSpellTitleIndex()
        index.add(listOf(title("Synthetic Protocol 47 Season 2")))
        matcher.rank("Synthetc Protocol 47", index.candidates("Synthetc Protocol 47", SearchMedium.VIDEO))
            .single().item.title shouldBe "Synthetic Protocol 47 Season 2"
    }

    @Test
    fun `runtime dictionary grounds corrections in full titles and aliases`() {
        val index = SymSpellTitleIndex(10)
        index.add(listOf(title("Solar;Gate", aliases = listOf("Golden Portal")), title("Lunar Garden")))
        matcher.rank("slar gate", index.candidates("slar gate", SearchMedium.VIDEO)).first().item.title shouldBe
            "Solar;Gate"
        matcher.rank("golden portal", index.candidates("golden portal", SearchMedium.VIDEO)).first().score shouldBe 100
        index.candidates("Solar", SearchMedium.MANGA) shouldBe emptyList()
        matcher.rank("slargate", index.candidates("slargate", SearchMedium.VIDEO)).first().item.title shouldBe
            "Solar;Gate"
        matcher.rank("Solar Garden", index.candidates("Solar Garden", SearchMedium.VIDEO)) shouldBe emptyList()
        index.clear()
        index.candidates("solar", SearchMedium.VIDEO) shouldBe emptyList()
    }

    @Test
    fun `library syntax is not corrected`() {
        LibraryTitleSearch.matches("id:123", "id 123", true) shouldBe false
        LibraryTitleSearch.matches("-Solar", "Solar", true) shouldBe false
        LibraryTitleSearch.matches("Solar,Gate", "Solar Gate", true) shouldBe false
        LibraryTitleSearch.matches("Slar Gate", "Solar Gate", false) shouldBe false
    }

    private class Provider(private val titles: List<SearchTitle>) : SearchCandidateProvider {
        var calls = 0
        override suspend fun candidates(query: String, medium: SearchMedium, online: Boolean): List<SearchTitle> {
            calls++
            delay(10)
            return titles
        }
        override suspend fun remember(items: List<SearchTitle>) = Unit
    }

    private class Adapter(
        override val key: String = "synthetic",
        val fetcher: suspend (Int, String) -> SearchPage<String>,
    ) : ExtensionSearchAdapter<String> {
        val calls = mutableListOf<Pair<Int, String>>()
        override fun identity(item: String) = item
        override fun title(item: String) = SearchTitle(item, item, SearchMedium.VIDEO)
        override suspend fun fetch(page: Int, query: String): SearchPage<String> {
            calls += page to query
            return fetcher(page, query)
        }
    }

    @Test
    fun `all sources share candidates while original text and source identity stay separate`() = runTest {
        val provider = Provider(listOf(title("Solar;Gate")))
        val session = SearchSession("slar gate", SearchMedium.VIDEO, provider, matcher)
        val one = Adapter("one") { _, q ->
            SearchPage(
                if (q ==
                    "Solar;Gate"
                ) {
                    listOf("Solar;Gate")
                } else {
                    emptyList()
                },
                false,
            )
        }
        val two = Adapter("two", one.fetcher)
        val a = async { session.search(one, 1) }
        val b = async { session.search(two, 1) }
        a.await().items shouldBe listOf("Solar;Gate")
        b.await().items shouldBe listOf("Solar;Gate")
        provider.calls shouldBe 1
        session.query shouldBe "slar gate"
        one.calls shouldBe listOf(1 to "slar gate", 1 to "Solar;Gate")
    }

    @Test
    fun `ambiguous candidates never silently choose an edition`() = runTest {
        val provider = Provider(listOf(title("Solar Gate 2"), title("Solar Gate 3")))
        val session = SearchSession("Solar Gate", SearchMedium.VIDEO, provider, matcher)
        val adapter = Adapter { _, _ -> SearchPage(emptyList(), false) }
        session.search(adapter, 1)
        session.assistance.value.correctedQuery shouldBe null
        adapter.calls.size shouldBe 2 // Original plus a bounded anchor, not a guessed season.
        session.assistance.value.suggestions.size shouldBe 2
    }

    @Test
    fun `errors and exact mode never trigger retries or reorder original results`() = runTest {
        val provider = Provider(listOf(title("Solar Gate")))
        val broken = Adapter { _, _ -> throw IOException("HTTP 429") }
        val session = SearchSession("slar gate", SearchMedium.VIDEO, provider, matcher)
        assertThrows<IOException> { session.search(broken, 1) }
        broken.calls.size shouldBe 1
        provider.calls shouldBe 0
        val exact = SearchSession("slar gate", SearchMedium.VIDEO, provider, matcher, exact = true)
        val original = Adapter { _, _ -> SearchPage(listOf("Other Title", "Solar Gate"), false) }
        exact.search(original, 1).items shouldBe listOf("Other Title", "Solar Gate")
        original.calls.size shouldBe 1
    }

    @Test
    fun `independent pagination preserves successful branch across an interrupted append`() = runTest {
        var fail = true
        val session = SearchSession("slar gate", SearchMedium.VIDEO, Provider(listOf(title("Solar Gate"))), matcher)
        val adapter = Adapter { page, query ->
            if (page == 1) {
                SearchPage(if (query == "Solar Gate") listOf("Solar Gate") else emptyList(), true)
            } else if (query == "slar gate") {
                SearchPage(listOf("Original Page Two"), false)
            } else if (fail) {
                fail = false
                throw IOException("interrupted")
            } else {
                SearchPage(listOf("Solar Gate", "Solar Gate Bonus"), false)
            }
        }
        session.search(adapter, 1)
        assertThrows<IOException> { session.search(adapter, 2) }
        session.search(adapter, 2).items shouldBe listOf("Original Page Two", "Solar Gate Bonus")
        adapter.calls.count { it == 2 to "slar gate" } shouldBe 1
        session.search(adapter, 3).hasNextPage shouldBe false
    }

    @Test
    fun `offline session does not call extensions`() = runTest {
        val adapter = Adapter { _, _ -> error("Network must not run") }
        SearchSession("Solar", SearchMedium.VIDEO, Provider(emptyList()), matcher, online = false, allowNetwork = false)
            .search(adapter, 1).items shouldBe emptyList()
        adapter.calls shouldBe emptyList()
    }

    @Test
    fun `a catalog outage retains local suggestions and successful original results`() = runTest {
        val provider = object : SearchCandidateProvider {
            override suspend fun candidates(query: String, medium: SearchMedium, online: Boolean): List<SearchTitle> =
                throw SearchCandidateFailure(listOf(title("Solar Gate")), IOException("timeout"))
            override suspend fun remember(items: List<SearchTitle>) = Unit
        }
        val session = SearchSession("slar gate", SearchMedium.VIDEO, provider, matcher)
        val adapter = Adapter { _, _ -> SearchPage(listOf("Original Partial"), false) }
        session.search(adapter, 1).items shouldBe listOf("Original Partial")
        session.assistance.value.unavailable shouldBe true
        session.assistance.value.suggestions.single().title shouldBe "Solar Gate"
        (adapter.calls.size <= 3) shouldBe true
    }

    @Test
    fun `aliases can recover canonical titles without changing the original query`() = runTest {
        val session = SearchSession(
            "golden portl",
            SearchMedium.VIDEO,
            Provider(listOf(title("Solar Gate", aliases = listOf("Golden Portal")))),
            matcher,
        )
        val adapter = Adapter { _, query ->
            SearchPage(if (query == "Solar Gate") listOf("Solar Gate") else emptyList(), false)
        }
        session.search(adapter, 1).items shouldBe listOf("Solar Gate")
        adapter.calls shouldBe listOf(1 to "golden portl", 1 to "Golden Portal", 1 to "Solar Gate")
    }

    @Test
    fun `partial typo can recover a full edition name without inventing words`() = runTest {
        val name = "Solar;Gate (Translated)"
        val session = SearchSession("slar gate", SearchMedium.VIDEO, Provider(listOf(title(name))), matcher)
        val adapter = Adapter { _, query -> SearchPage(if (query == name) listOf(name) else emptyList(), false) }
        session.search(adapter, 1).items shouldBe listOf(name)
        adapter.calls shouldBe listOf(1 to "slar gate", 1 to name)
        session.query shouldBe "slar gate"
    }

    @Test
    fun `numberless aliases do not create false ambiguity with the base work`() = runTest {
        val base = title("Solar;Gate")
        val side = title("Solar;Gate 0: Synthetic Side Story", aliases = listOf("Solar;Gate: Synthetic Side Story"))
        val ranked = matcher.rank("slar gate", listOf(base, side))
        ranked.first().item shouldBe base
        (ranked.first().score - ranked.last().score >= 6) shouldBe true
        val session = SearchSession("slar gate", SearchMedium.VIDEO, Provider(listOf(base, side)), matcher)
        val adapter = Adapter { _, query ->
            SearchPage(if (query == "Solar;Gate") listOf("Solar;Gate") else emptyList(), false)
        }
        session.search(adapter, 1).items shouldBe listOf("Solar;Gate")
        session.assistance.value.correctedQuery shouldBe "Solar;Gate"
    }

    @Test
    fun `shared aliases do not prove different canonical works are the same`() = runTest {
        val titles = listOf(
            title("Solar Gate", aliases = listOf("Golden Portal")),
            title("Lunar Gate", aliases = listOf("Golden Portal")),
        )
        val session = SearchSession("golden portl", SearchMedium.VIDEO, Provider(titles), matcher)
        val adapter = Adapter { _, variant ->
            SearchPage(if (variant == "Golden Portal") listOf("Solar Gate", "Lunar Gate") else emptyList(), false)
        }
        session.search(adapter, 1).items.toSet() shouldBe setOf("Solar Gate", "Lunar Gate")
        session.assistance.value.correctedQuery shouldBe "Golden Portal"
        session.assistance.value.suggestions.toSet() shouldBe titles.toSet()
        adapter.calls shouldBe listOf(1 to "golden portl", 1 to "Golden Portal")
    }

    @Test
    fun `plausible alternatives recover results while keeping every suggestion visible`() = runTest {
        val titles = listOf(title("Solar Gate"), title("Solar Gaze"))
        val session = SearchSession("solar gte", SearchMedium.VIDEO, Provider(titles), matcher)
        val adapter = Adapter { _, variant ->
            SearchPage(if (variant == "Solar Gate") listOf("Solar Gate") else emptyList(), false)
        }
        session.search(adapter, 1).items shouldBe listOf("Solar Gate")
        session.query shouldBe "solar gte"
        session.assistance.value.suggestions.size shouldBe 2
        session.assistance.value.correctedQuery shouldBe "Solar Gate"
    }

    @Test
    fun `zero numbered work is recovered without accepting the unnumbered or another season`() = runTest {
        val zero = "Solar;Gate 0: Synthetic Continuation"
        val titles = listOf(title("Solar;Gate"), title(zero), title("Solar;Gate 2"))
        val session = SearchSession("slar gate 0", SearchMedium.VIDEO, Provider(titles), matcher)
        val adapter = Adapter { _, variant ->
            SearchPage(if (variant == zero) listOf("Solar;Gate", zero, "Solar;Gate 2") else emptyList(), false)
        }
        session.search(adapter, 1).items shouldBe listOf(zero)
        session.assistance.value.correctedQuery shouldBe zero
        session.assistance.value.suggestions.first().title shouldBe zero
    }

    @Test
    fun `trailing first season can retrieve the base title without discarding other numbers`() = runTest {
        for ((query, base) in listOf(
            "slar gate 1" to "Solar;Gate",
            "slar gate season 1" to "Solar;Gate",
            "slar gate stagione 1" to "Solar;Gate",
            "synthetc protocol 47 1" to "Synthetic Protocol 47",
        )) {
            val session = SearchSession(query, SearchMedium.VIDEO, Provider(listOf(title(base))), matcher)
            val adapter = Adapter { _, variant ->
                SearchPage(if (variant == base) listOf(base, "Synthetic Protocol 48") else emptyList(), false)
            }
            session.search(adapter, 1).items shouldBe listOf(base)
            session.assistance.value.correctedQuery shouldBe base
            session.query shouldBe query
        }
        matcher.rank("Solar Gate 0", listOf(title("Solar Gate"))).single().numericFallback shouldBe true
        matcher.rank("Solar Gate 2", listOf(title("Solar Gate"))).single().numericFallback shouldBe true
        matcher.score("Solar1 Gate", "Solar Gate") shouldBe 0
        matcher.score("Synthetic Protocol 48 1", "Synthetic Protocol 47") shouldBe 0
        val index = SymSpellTitleIndex()
        index.add(listOf(title("Solar;Gate"), title("Solar;Gate 1"), title("Solar;Gate 2")))
        val ranked = matcher.rank("solar gate 1", index.candidates("solar gate 1", SearchMedium.VIDEO))
        ranked.first().item.title shouldBe "Solar;Gate 1"
        ranked.map { it.item.title } shouldBe listOf("Solar;Gate 1", "Solar;Gate")
    }

    @Test
    fun `an actual first number in a name takes priority over the unnumbered interpretation`() = runTest {
        val titles = listOf(title("Solar;Gate"), title("Solar;Gate 1"))
        val session = SearchSession("slar gate 1", SearchMedium.VIDEO, Provider(titles), matcher)
        val adapter = Adapter { _, variant ->
            SearchPage(if (variant in listOf("Solar;Gate", "Solar;Gate 1")) listOf(variant) else emptyList(), false)
        }
        session.search(adapter, 1).items shouldBe listOf("Solar;Gate 1")
        adapter.calls shouldBe listOf(1 to "slar gate 1", 1 to "Solar;Gate 1")
    }

    @Test
    fun `unmatched trailing numbers recover a known base and preserve numbers inside its name`() = runTest {
        for ((query, name) in listOf(
            "slar gate 56" to "Solar;Gate",
            "slar gate season 56" to "Solar;Gate",
            "synthetc protocol 47 56" to "Synthetic Protocol 47",
        )) {
            val titles = listOf(title(name), title("Solar;Gate 0"), title("Synthetic Protocol 48"))
            val index = SymSpellTitleIndex()
            index.add(titles)
            val session = SearchSession(
                query,
                SearchMedium.VIDEO,
                Provider(index.candidates(query, SearchMedium.VIDEO)),
                matcher,
            )
            val adapter = Adapter { _, variant ->
                SearchPage(
                    if (variant ==
                        name
                    ) {
                        listOf(name)
                    } else {
                        emptyList()
                    },
                    false,
                )
            }
            session.search(adapter, 1).items shouldBe listOf(name)
            session.assistance.value.correctedQuery shouldBe name
            adapter.calls shouldBe listOf(1 to query, 1 to name)
        }
    }

    @Test
    fun `a matched numbered candidate stays strict across refresh and pagination`() = runTest {
        val name = "Solar;Gate 0"
        val session = SearchSession(
            "slar gate 0",
            SearchMedium.VIDEO,
            Provider(listOf(title("Solar;Gate"), title(name))),
            matcher,
        )
        val adapter = Adapter { page, variant ->
            SearchPage(
                if (variant == name) listOf("Solar;Gate", name, "Solar;Gate 2", "$name Bonus") else emptyList(),
                page == 1 && variant == name,
            )
        }
        session.search(adapter, 1).items shouldBe listOf(name, "$name Bonus")
        session.search(adapter, 2).items shouldBe emptyList()
        session.search(adapter, 1).items shouldBe listOf(name, "$name Bonus")
    }

    @Test
    fun `identical suggestion text has one chip without collapsing source results`() = runTest {
        val titles = listOf(title("Solar Gate", "one"), title("Solar Gate", "two"), title("Solar Gaze"))
        val session = SearchSession("solar gte", SearchMedium.VIDEO, Provider(titles), matcher)
        session.suggestions().size shouldBe 3
        session.assistance.value.suggestions.map { it.title } shouldBe listOf("Solar Gate", "Solar Gaze")
    }

    @Test
    fun `anchors use the original intent rather than an unrelated subtitle word`() = runTest {
        val full = "Aerial;Gate - A Chronicle Beyond Borders"
        for (query in listOf("aeril gate", "aerilgate")) {
            val session = SearchSession(query, SearchMedium.VIDEO, Provider(listOf(title(full))), matcher)
            val adapter = Adapter { _, variant ->
                SearchPage(if (variant == "aerial") listOf("Aerial;Gate (Translated)") else emptyList(), true)
            }
            session.search(adapter, 1).items shouldBe listOf("Aerial;Gate (Translated)")
            adapter.calls shouldBe listOf(1 to query, 1 to full, 1 to "aerial")
            session.search(adapter, 2).items shouldBe emptyList()
        }
    }

    @Test
    fun `refresh reuses verified source spelling without exhausting discovery retries`() = runTest {
        val provider = Provider(listOf(title("Solar Gate", aliases = listOf("Golden Portal"))))
        val session = SearchSession("golden portl", SearchMedium.VIDEO, provider, matcher)
        var revision = 1
        val adapter = Adapter { _, query ->
            SearchPage(if (query == "Solar Gate") listOf("Solar Gate Bonus $revision") else emptyList(), false)
        }
        session.search(adapter, 1).items shouldBe listOf("Solar Gate Bonus 1")
        revision = 2
        session.search(adapter, 1).items shouldBe listOf("Solar Gate Bonus 2")
        provider.calls shouldBe 1
        adapter.calls.takeLast(2) shouldBe listOf(1 to "golden portl", 1 to "Solar Gate")
    }

    @Test
    fun `cancelled candidate lookup cannot publish suggestions`() = runTest {
        val provider = object : SearchCandidateProvider {
            override suspend fun candidates(query: String, medium: SearchMedium, online: Boolean): List<SearchTitle> {
                delay(10_000)
                return listOf(title("Solar Gate"))
            }
            override suspend fun remember(items: List<SearchTitle>) = Unit
        }
        val session = SearchSession("slar gate", SearchMedium.VIDEO, provider, matcher)
        val lookup = async { session.suggestions() }
        delay(10)
        lookup.cancel()
        lookup.join()
        session.assistance.value.suggestions shouldBe emptyList()
        session.assistance.value.loading shouldBe false
    }

    @Test
    fun `request budgets survive retries and anchors never accept unrelated works`() = runTest {
        val budget = SearchRequestBudget(3)
        repeat(3) { budget.take() shouldBe true }
        budget.take() shouldBe false
        val session = SearchSession("slar gate", SearchMedium.VIDEO, Provider(listOf(title("Solar Gate"))), matcher)
        val adapter = Adapter { _, query ->
            SearchPage(if (query == "solar") listOf("Solar Garden") else emptyList(), true)
        }
        session.search(adapter, 1).items shouldBe emptyList()
        session.search(adapter, 1).items shouldBe emptyList()
        adapter.calls.count { it.second != "slar gate" } shouldBe 2
    }
}

package tachiyomi.domain.search

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class TitleSpellingRecoveryTest {
    private val matcher = LexicalTitleMatcher()
    private val full = "Nova:Loop - The Synthetic Awakening"
    private val family = listOf(full, "Nova:Loop 2 - The Synthetic Return", "Nova:Loop - Synthetic Side Story")

    private class Provider(var titles: List<SearchTitle>) : SearchCandidateProvider {
        var calls = 0
        override suspend fun candidates(query: String, medium: SearchMedium, online: Boolean): List<SearchTitle> {
            calls++
            return titles
        }
        override suspend fun remember(items: List<SearchTitle>) {
            titles = (titles + items).distinctBy { it.key }
        }
    }

    private class Adapter(val fetcher: (Int, String) -> SearchPage<String>) : ExtensionSearchAdapter<String> {
        override val key = "synthetic"
        val calls = mutableListOf<Pair<Int, String>>()
        override fun identity(item: String) = item
        override fun title(item: String) = SearchTitle(item, item, SearchMedium.VIDEO)
        override suspend fun fetch(page: Int, query: String): SearchPage<String> {
            calls += page to query
            return fetcher(page, query)
        }
    }

    private fun provider() = Provider(listOf(SearchTitle("catalog:one", full, SearchMedium.VIDEO)))

    @Test
    fun `a cold short query recovers the complete family rather than the first full title`() = runTest {
        for (query in listOf("nova loop", "novaloop", "nova-loop", "Nova:Loop")) {
            val adapter = Adapter { _, variant ->
                SearchPage(
                    when (variant) {
                        "Nova:Loop" -> family
                        full -> listOf(full)
                        else -> emptyList()
                    },
                    false,
                )
            }
            val session = SearchSession(query, SearchMedium.VIDEO, provider(), matcher)
            session.search(adapter, 1).items.toSet() shouldBe family.toSet()
            session.query shouldBe query
            (adapter.calls.size <= 3) shouldBe true
            adapter.calls.none { it.second == full } shouldBe true
        }
    }

    @Test
    fun `one strong original match does not suppress equivalent spelling recovery`() = runTest {
        val adapter = Adapter { _, variant ->
            SearchPage(if (variant == "Nova:Loop") family else listOf(full), false)
        }
        val session = SearchSession("nova loop", SearchMedium.VIDEO, provider(), matcher)
        session.search(adapter, 1).items.toSet() shouldBe family.toSet()
        adapter.calls shouldBe listOf(1 to "nova loop", 1 to "Nova:Loop")
        session.assistance.value.correctedQuery shouldBe "Nova:Loop"
    }

    @Test
    fun `both grounded spellings remain available through pagination and refresh`() = runTest {
        val provider = Provider(listOf(SearchTitle("catalog:one", full, SearchMedium.VIDEO, listOf("NovaLoop"))))
        val adapter = Adapter { page, variant ->
            SearchPage(
                when (variant) {
                    "Nova:Loop" -> if (page == 1) listOf(full) else listOf(family[1])
                    "NovaLoop" -> if (page == 1) listOf(family[2]) else listOf("Nova:Loop - Synthetic Bonus")
                    else -> emptyList()
                },
                page == 1 && variant in listOf("Nova:Loop", "NovaLoop"),
            )
        }
        val session = SearchSession("nova loop", SearchMedium.VIDEO, provider, matcher)
        session.search(adapter, 1).items.toSet() shouldBe setOf(full, family[2])
        session.search(adapter, 2).items.toSet() shouldBe setOf(family[1], "Nova:Loop - Synthetic Bonus")
        session.search(adapter, 3).hasNextPage shouldBe false
        session.search(adapter, 1).items.toSet() shouldBe setOf(full, family[2])
        provider.calls shouldBe 1
        adapter.calls.takeLast(3) shouldBe listOf(1 to "nova loop", 1 to "Nova:Loop", 1 to "NovaLoop")
    }

    @Test
    fun `cold and warm runtime data give the same available family`() = runTest {
        val provider = provider()
        repeat(3) {
            val adapter = Adapter { _, variant ->
                SearchPage(
                    if (variant ==
                        "Nova:Loop"
                    ) {
                        family
                    } else {
                        emptyList()
                    },
                    false,
                )
            }
            SearchSession("nova loop", SearchMedium.VIDEO, provider, matcher)
                .search(adapter, 1).items.toSet() shouldBe family.toSet()
        }
    }

    @Test
    fun `numbered short spelling preserves its number across subsequent pages`() = runTest {
        val provider = Provider(listOf(SearchTitle("catalog:one", family[1], SearchMedium.VIDEO)))
        val adapter = Adapter { page, variant ->
            SearchPage(
                if (variant ==
                    "Nova:Loop 2"
                ) {
                    family + "Nova:Loop 2 - Synthetic Bonus"
                } else {
                    emptyList()
                },
                page == 1,
            )
        }
        val session = SearchSession("nova loop 2", SearchMedium.VIDEO, provider, matcher)
        session.search(adapter, 1).items.toSet() shouldBe setOf(family[1], "Nova:Loop 2 - Synthetic Bonus")
        session.search(adapter, 2).items shouldBe emptyList()
        adapter.calls.first { it.second != "nova loop 2" }.second shouldBe "Nova:Loop 2"
    }

    @Test
    fun `exact mode never expands spelling and recovered results reject unrelated titles`() = runTest {
        val adapter = Adapter { _, variant ->
            SearchPage(
                if (variant ==
                    "Nova:Loop"
                ) {
                    family + "Nova Garden"
                } else {
                    emptyList()
                },
                false,
            )
        }
        val exact = SearchSession("nova loop", SearchMedium.VIDEO, provider(), matcher, exact = true)
        exact.search(adapter, 1).items shouldBe emptyList()
        adapter.calls shouldBe listOf(1 to "nova loop")
        SearchSession("nova loop", SearchMedium.VIDEO, provider(), matcher)
            .search(adapter, 1).items.toSet() shouldBe family.toSet()
    }

    @Test
    fun `equivalent fragments preserve separators digits and the requested title scope`() {
        TitleNormalizer.equivalentSpellings("nova loop", listOf(full)) shouldBe listOf("Nova:Loop")
        TitleNormalizer.equivalentSpellings("nova loop 2", family) shouldBe listOf("Nova:Loop 2")
        TitleNormalizer.equivalentSpellings("protocol 47", listOf("The Protocol-47: Synthetic Volume")) shouldBe
            listOf("Protocol-47")
        TitleNormalizer.equivalentSpellings("nova loop 3", family) shouldBe emptyList()
        TitleNormalizer.equivalentSpellings("xy", listOf("X:Y Volume")) shouldBe emptyList()
    }
}

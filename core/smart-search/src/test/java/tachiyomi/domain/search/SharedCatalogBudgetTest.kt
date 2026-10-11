package tachiyomi.domain.search

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SharedCatalogBudgetTest {
    @Test
    fun `two media share catalog allowance without blocking original source requests`() = runTest {
        val budget = SearchRequestBudget(3)
        var requests = 0
        val sessions = SearchMedium.entries.map { medium ->
            SearchSession(
                "synthetic typo",
                medium,
                object : SearchCandidateProvider {
                    override suspend fun candidates(
                        query: String,
                        medium: SearchMedium,
                        online: Boolean,
                    ): List<SearchTitle> {
                        repeat(2) { if (budget.take()) requests++ }
                        return emptyList()
                    }
                    override suspend fun remember(items: List<SearchTitle>) = Unit
                },
                LexicalTitleMatcher(),
            )
        }
        sessions.map { async { it.suggestions() } }.awaitAll()
        requests shouldBe 3
        budget.take() shouldBe false
        sessions.forEachIndexed { index, session ->
            val adapter = object : ExtensionSearchAdapter<String> {
                override val key = "synthetic-source-$index"
                override fun identity(item: String) = item
                override fun title(item: String) = SearchTitle(item, item, SearchMedium.entries[index])
                override suspend fun fetch(page: Int, query: String) = SearchPage(listOf(query), false)
            }
            session.search(adapter, 1).items shouldBe listOf("synthetic typo")
        }
    }
}

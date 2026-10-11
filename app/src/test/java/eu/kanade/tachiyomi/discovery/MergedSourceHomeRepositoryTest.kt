package eu.kanade.tachiyomi.discovery

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.discovery.MergedSourceHomeRepository
import tachiyomi.data.discovery.mergeHomeCards
import tachiyomi.domain.discovery.SectionState
import tachiyomi.domain.discovery.SourceHomeAccess
import tachiyomi.domain.discovery.SourceHomeFilter
import tachiyomi.domain.discovery.SourceHomeGroup
import tachiyomi.domain.discovery.SourceHomeGroupAccess
import tachiyomi.domain.discovery.SourceHomePage
import tachiyomi.domain.discovery.SourceHomePresentation
import tachiyomi.domain.discovery.SourceHomeRepository
import tachiyomi.domain.discovery.SourceHomeRequest
import tachiyomi.domain.discovery.SourceHomeSection
import tachiyomi.domain.discovery.SourceHomeSource
import tachiyomi.domain.discovery.homePresentation
import tachiyomi.domain.entries.anime.model.Anime

class MergedSourceHomeRepositoryTest {
    @Test fun browseAndFilterCursorsCannotInheritTheEndOfAnotherCatalogue() = runBlocking {
        val calls = mutableListOf<SourceHomeRequest>()
        val merged = MergedSourceHomeRepository({
            provider { request ->
                calls += request
                flowOf(page())
            }
        }, { access })
        val initial = SourceHomeRequest("popular")
        merged.observe(access, initial).last()
        calls.clear()
        merged.observe(access, initial.copy(page = 2)).last()
        assertTrue(calls.isEmpty())
        merged.observe(access, initial.copy(page = 2, browse = true)).last()
        assertTrue(calls.isNotEmpty())
        calls.clear()
        merged.observe(access, initial.copy(page = 2, filters = mapOf("Category" to listOf("Adventure")))).last()
        assertTrue(calls.isNotEmpty())
    }

    @Test fun selectedDateOnlyQueriesCapableProvidersAndKeepsItsOwnPagination() = runBlocking {
        val dated = second.copy(sections = listOf(shelf.copy(dateFilter = "Date")))
        val current = access.copy(
            group = access.group!!.copy(providers = listOf(first, dated)),
            providers = listOf(SourceHomeAccess(first), SourceHomeAccess(dated)),
        )
        val calls = mutableListOf<Pair<Long, SourceHomeRequest>>()
        val merged = MergedSourceHomeRepository({ source ->
            provider { request ->
                calls += source.id to request
                flowOf(page())
            }
        }, { current })
        assertTrue(current.group!!.sections.single().supportsDate)
        val tomorrow = SourceHomeRequest("popular", date = "2026-09-12")
        merged.observe(current, tomorrow).last()
        merged.observe(current, tomorrow.copy(page = 2)).last()
        merged.observe(current, tomorrow.copy(page = 2, date = "2026-09-13")).last()
        assertEquals(listOf(2L to tomorrow, 2L to tomorrow.copy(page = 2, date = "2026-09-13")), calls)
    }

    @Test fun mergedFeedsPreserveDifferentEpisodesOfOneSeries() = runBlocking {
        val a = item(11, 1)
        val cards = listOf("ep9", "ep8", "ep9").map { id ->
            a.copy(memo = SourceHomePresentation(id = id).attachTo(a.memo))
        }
        val merged = MergedSourceHomeRepository({ source ->
            provider { flowOf(if (source.id == 1L) page(*cards.toTypedArray()) else page()) }
        }, { access })
        val result = merged.observe(access, SourceHomeRequest("popular")).last()
        assertEquals(listOf("ep9", "ep8"), result.data!!.items.map { it.homePresentation?.id })
        assertTrue(result.data!!.items.all { it.id == 11L })
    }

    private val shelf = SourceHomeSection("popular", "Popolari", emptyMap())
    private val first =
        SourceHomeSource(
            1,
            "v1",
            listOf(shelf),
            emptyList(),
            title = "Cartoni",
            sourceName = "Uno",
            homeId = "cartoons",
        )
    private val second = first.copy(id = 2, key = "two", sourceName = "Due")
    private val access = SourceHomeGroupAccess(
        SourceHomeGroup("cartoons", "Cartoni", listOf(first, second)),
        listOf(SourceHomeAccess(first), SourceHomeAccess(second)),
    )
    private fun item(
        id: Long,
        source: Long,
    ) = Anime.create().copy(id = id, source = source, url = "/$id", title = "Titolo uguale")
    private fun page(
        vararg items: Anime,
        next: Boolean = false,
    ) = SectionState(SourceHomePage(items.toList(), next), loading = false)
    private fun provider(
        load: (SourceHomeRequest) -> Flow<SectionState<SourceHomePage>>,
    ) = object : SourceHomeRepository {
        override fun observe(access: SourceHomeAccess, request: SourceHomeRequest, refresh: Boolean) = load(request)
    }

    @Test fun interleavesSourcesWithoutConfusingHomonymousTitles() = runBlocking {
        val a = item(11, 1)
        val b = item(12, 1)
        val c = item(21, 2)
        val merged = MergedSourceHomeRepository({ source ->
            provider { flowOf(if (source.id == 1L) page(a, a, b) else page(c)) }
        }, { access })
        val result = merged.observe(access, SourceHomeRequest("popular")).last()
        assertEquals(listOf(a, c, b), result.data!!.items)
        assertFalse(result.loading)
    }

    @Test fun matchingCatalogueIdsMergeDifferentTitlesAndKeepBothSources() = runBlocking {
        val a = item(11, 1).copy(title = "Name: Season 2")
        val b = item(21, 2).copy(title = "Name Season Two")
        val left = a.copy(memo = SourceHomePresentation(catalogIds = mapOf("catalog" to 42)).attachTo(a.memo))
        val right = b.copy(memo = SourceHomePresentation(catalogIds = mapOf("catalog" to 42)).attachTo(b.memo))
        val merged = MergedSourceHomeRepository({ source ->
            provider { flowOf(page(if (source.id == 1L) left else right)) }
        }, { access })
        val cards = merged.observe(access, SourceHomeRequest("popular")).last().data!!.items
        assertEquals(1, cards.size)
        assertEquals(listOf(1L, 2L), cards.single().homePresentation!!.choices.map { it.sourceId })
    }

    @Test fun conflictingCatalogueIdsNeverMergeHomonymousSeasons() = runBlocking {
        val a = item(11, 1).copy(
            memo = SourceHomePresentation(catalogIds = mapOf("catalog" to 42))
                .attachTo(item(11, 1).memo),
        )
        val b = item(21, 2).copy(
            memo = SourceHomePresentation(catalogIds = mapOf("catalog" to 43))
                .attachTo(item(21, 2).memo),
        )
        val merged = MergedSourceHomeRepository({ source ->
            provider { flowOf(page(if (source.id == 1L) a else b)) }
        }, { access })
        assertEquals(2, merged.observe(access, SourceHomeRequest("popular")).last().data!!.items.size)
    }

    @Test fun aSharedSecondaryIdCannotBridgeConflictingPrimaryIds() = runBlocking {
        fun card(id: Long, source: Long, ids: Map<String, Long>): Anime {
            val anime = item(id, source)
            return anime.copy(memo = SourceHomePresentation(catalogIds = ids).attachTo(anime.memo))
        }
        val firstCard = card(11, 1, mapOf("primary" to 10L))
        val bridge = card(21, 2, mapOf("primary" to 10L, "secondary" to 20L))
        val conflicting = card(31, 3, mapOf("primary" to 11L, "secondary" to 20L))
        val third = second.copy(id = 3, key = "three", sourceName = "Tre")
        val group = access.copy(
            group = access.group!!.copy(providers = listOf(first, second, third)),
            providers = listOf(SourceHomeAccess(first), SourceHomeAccess(second), SourceHomeAccess(third)),
        )
        val merged = MergedSourceHomeRepository({ source ->
            provider {
                flowOf(
                    page(
                        when (source.id) {
                            1L -> firstCard
                            2L -> bridge
                            else -> conflicting
                        },
                    ),
                )
            }
        }, { group })
        val cards = merged.observe(group, SourceHomeRequest("popular")).last().data!!.items
        assertEquals(2, cards.size)
        assertEquals(listOf(1L, 2L), cards.first().homePresentation!!.choices.map { it.sourceId })
    }

    @Test fun laterPagesMergeChoicesWithoutLosingEarlierSources() {
        fun card(id: Long, source: Long, catalogueId: Long): Anime {
            val anime = item(id, source)
            return anime.copy(
                memo = SourceHomePresentation(catalogIds = mapOf("catalog" to catalogueId))
                    .attachTo(anime.memo),
            )
        }
        val firstPage = mergeHomeCards(listOf(card(11, 1, 42)))
        val secondPage = mergeHomeCards(firstPage + card(21, 2, 42))
        val thirdPage = mergeHomeCards(secondPage + card(31, 3, 42))
        assertEquals(1, thirdPage.size)
        assertEquals(listOf(1L, 2L, 3L), thirdPage.single().homePresentation!!.choices.map { it.sourceId })
        assertEquals(3, mergeHomeCards(thirdPage + card(32, 3, 42) + card(41, 4, 43)).size)
    }

    @Test fun searchOnlyQueriesProvidersThatAcceptChosenFilter() = runBlocking {
        val filter = SourceHomeFilter("Genre", SourceHomeFilter.Kind.MULTIPLE, listOf("Fantasy"))
        val limited = first.copy(search = shelf, browseFilters = listOf(filter))
        val other = second.copy(search = shelf, browseFilters = emptyList())
        val group = access.copy(
            group = SourceHomeGroup("cartoons", "Cartoni", listOf(limited, other)),
            providers = listOf(SourceHomeAccess(limited), SourceHomeAccess(other)),
        )
        val queried = mutableListOf<Long>()
        val merged = MergedSourceHomeRepository({ source ->
            provider {
                queried += source.id
                flowOf(page(item(source.id, source.id)))
            }
        }, { group })
        merged.observe(
            group,
            SourceHomeRequest(SourceHomeRequest.SEARCH, filters = mapOf("Genre" to listOf("Fantasy"))),
        ).last()
        assertEquals(listOf(1L), queried)
    }

    @Test fun failingProviderKeepsHealthyResultsAndNamesOnlyTheFailedSource() = runBlocking {
        val merged = MergedSourceHomeRepository({ source ->
            provider {
                flowOf(if (source.id == 1L) page(item(1, 1)) else SectionState(loading = false, error = "Errore"))
            }
        }, { access })
        val result = merged.observe(access, SourceHomeRequest("popular")).last()
        assertEquals(1, result.data!!.items.size)
        assertEquals("Due: Errore", result.error)
    }

    @Test fun slowProviderDoesNotDelayHealthyCards() = runBlocking {
        val never = CompletableDeferred<Unit>()
        val merged = MergedSourceHomeRepository({ source ->
            provider {
                if (source.id == 1L) {
                    flowOf(page(item(1, 1)))
                } else {
                    flow {
                        never.await()
                        emit(page())
                    }
                }
            }
        }, { access })
        val result = withTimeout(2_000) {
            merged.observe(access, SourceHomeRequest("popular")).first { it.data?.items?.isNotEmpty() == true }
        }
        assertTrue(result.loading)
        assertEquals(1, result.data!!.items.size)
    }

    @Test fun unavailableOrOfflineGroupNeverLoadsProviders() = runBlocking {
        val merged = MergedSourceHomeRepository({ error("Must not fetch") }, { access.copy(group = null) })
        assertNull(merged.observe(access, SourceHomeRequest("popular")).last().data)
        val offline = access.copy(offline = true)
        assertNull(merged.observe(offline, SourceHomeRequest("popular")).last().data)
    }

    @Test fun removedSourceCannotLeakLateResults() = runBlocking {
        var current = access
        val merged =
            MergedSourceHomeRepository({
                provider {
                    flow {
                        current = access.copy(group = null)
                        emit(page(item(1, 1)))
                    }
                }
            }, { current })
        assertNull(merged.observe(access, SourceHomeRequest("popular")).last().data)
    }

    @Test fun paginationStopsEachSourceIndependentlyAndRefreshRestartsIt() = runBlocking {
        val calls = mutableListOf<Pair<Long, Int>>()
        val merged = MergedSourceHomeRepository({ source ->
            provider { request ->
                calls += source.id to request.page
                flowOf(page(item(source.id * 10 + request.page, source.id), next = source.id == 2L))
            }
        }, { access })
        merged.observe(access, SourceHomeRequest("popular")).last()
        merged.observe(access, SourceHomeRequest("popular", 2)).last()
        assertFalse(1L to 2 in calls)
        assertTrue(2L to 2 in calls)
        merged.observe(access, SourceHomeRequest("popular", 2), refresh = true).last()
        assertTrue(1L to 2 in calls)
    }

    @Test fun mergedSectionsAreUniqueAndOnlySupportedSourcesAreQueried() = runBlocking {
        val extra = SourceHomeSection("recent", "Recenti", emptyMap())
        val changed = second.copy(sections = listOf(shelf, extra))
        val group = access.copy(
            group = access.group!!.copy(providers = listOf(first, changed)),
            providers = listOf(SourceHomeAccess(first), SourceHomeAccess(changed)),
        )
        assertEquals(listOf("popular", "recent"), group.group!!.sections.map { it.id })
        val merged = MergedSourceHomeRepository({ source ->
            assertEquals(2L, source.id)
            provider { flowOf(page(item(2, 2))) }
        }, { group })
        assertEquals(2L, merged.observe(group, SourceHomeRequest("recent")).last().data!!.items.single().source)
    }
}

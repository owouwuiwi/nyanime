package eu.kanade.tachiyomi.discovery

import eu.kanade.tachiyomi.ui.discovery.SourceHomeFeedLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.discovery.SectionState
import tachiyomi.domain.discovery.SourceHomeGroup
import tachiyomi.domain.discovery.SourceHomeGroupAccess
import tachiyomi.domain.discovery.SourceHomeGroupRepository
import tachiyomi.domain.discovery.SourceHomePage
import tachiyomi.domain.discovery.SourceHomeRequest
import tachiyomi.domain.entries.anime.model.Anime

class SourceHomeFeedLoaderTest {
    @Test fun returningAndRefreshingCannotReplaceSavedCardsWithIntermediateProviderSubsets() = runBlocking {
        val access = SourceHomeGroupAccess(group = SourceHomeGroup("test", "Home", emptyList()))
        val first = Anime.create().copy(source = 42, url = "/series/one", thumbnailUrl = "old.jpg")
        val second = first.copy(url = "/series/two")
        val saved = SourceHomePage(listOf(first, second), false)
        val fresh = SourceHomePage(listOf(first.copy(thumbnailUrl = "new.jpg"), second), false)
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val repository = object : SourceHomeGroupRepository {
            override fun observe(access: SourceHomeGroupAccess, request: SourceHomeRequest, refresh: Boolean) = flow {
                if (++calls == 1) {
                    emit(SectionState(saved, loading = false))
                } else {
                    emit(SectionState())
                    emit(SectionState(SourceHomePage(listOf(second), false), loading = true))
                    gate.await()
                    emit(SectionState(fresh, loading = false))
                }
            }
        }
        val loader =
            SourceHomeFeedLoader(CoroutineScope(coroutineContext + Dispatchers.Unconfined), repository) { access }
        loader.load(SourceHomeRequest("featured"))
        loader.pause()
        loader.resume()
        assertEquals(saved, loader.sections.value["featured"]?.data)
        assertTrue(loader.sections.value["featured"]!!.loading)
        loader.refresh(true)
        assertEquals(saved, loader.sections.value["featured"]?.data)
        gate.complete(Unit)
        assertEquals(fresh, loader.sections.value["featured"]?.data)
        assertEquals(false, loader.sections.value["featured"]?.loading)
        loader.reset()
    }

    @Test fun firstLoadStillPresentsPartialResultsWithoutWaitingForTheLastProvider() = runBlocking {
        val access = SourceHomeGroupAccess(group = SourceHomeGroup("test", "Home", emptyList()))
        val first = Anime.create().copy(source = 42, url = "/series/one")
        val partial = SourceHomePage(listOf(first), false)
        val complete = SourceHomePage(listOf(first, first.copy(url = "/series/two")), false)
        val gate = CompletableDeferred<Unit>()
        val repository = object : SourceHomeGroupRepository {
            override fun observe(access: SourceHomeGroupAccess, request: SourceHomeRequest, refresh: Boolean) = flow {
                emit(SectionState(partial, loading = true))
                gate.await()
                emit(SectionState(complete, loading = false))
            }
        }
        val loader =
            SourceHomeFeedLoader(CoroutineScope(coroutineContext + Dispatchers.Unconfined), repository) { access }
        loader.load(SourceHomeRequest("featured"))
        assertEquals(partial, loader.sections.value["featured"]?.data)
        gate.complete(Unit)
        assertEquals(complete, loader.sections.value["featured"]?.data)
        loader.reset()
    }

    @Test fun hiddenHomeCancelsRemoteWorkAndResumesWithoutLosingCardsOrDates() = runBlocking {
        val access = SourceHomeGroupAccess(group = SourceHomeGroup("test", "Home", emptyList()))
        var calls = 0
        var cancelled = 0
        val page = SourceHomePage(emptyList(), false, "Saved calendar")
        val repository = object : SourceHomeGroupRepository {
            override fun observe(access: SourceHomeGroupAccess, request: SourceHomeRequest, refresh: Boolean) = flow {
                calls++
                emit(SectionState(page, loading = true))
                try {
                    awaitCancellation()
                } finally {
                    cancelled++
                }
            }
        }
        val loader =
            SourceHomeFeedLoader(CoroutineScope(coroutineContext + Dispatchers.Unconfined), repository) { access }
        val request = SourceHomeRequest("schedule", date = "2026-09-12")
        loader.load(request)
        loader.pause()
        assertEquals(1, cancelled)
        assertEquals(page, loader.sections.value["schedule"]?.data)
        assertEquals(false, loader.sections.value["schedule"]?.loading)
        loader.refresh(true)
        loader.load(SourceHomeRequest("other"))
        assertEquals(1, calls)
        loader.resume()
        assertEquals(2, calls)
        loader.reset()
        assertEquals(2, cancelled)
    }

    @Test fun returnRevalidatesVisitedRequestsWhileManualRefreshForcesNetwork() = runBlocking {
        val calls = mutableListOf<Pair<SourceHomeRequest, Boolean>>()
        val access = SourceHomeGroupAccess(group = SourceHomeGroup("test", "Home", emptyList()))
        val repository = object : SourceHomeGroupRepository {
            override fun observe(access: SourceHomeGroupAccess, request: SourceHomeRequest, refresh: Boolean) = flow {
                calls += request to refresh
                emit(SectionState(SourceHomePage(emptyList(), false), loading = false))
            }
        }
        val loader =
            SourceHomeFeedLoader(CoroutineScope(coroutineContext + Dispatchers.Unconfined), repository) { access }
        val dated = SourceHomeRequest("schedule", date = "2026-09-12")
        loader.load(dated)
        loader.load(dated)
        assertEquals(1, calls.size)
        loader.refresh(false)
        loader.refresh(true)
        assertEquals(listOf(dated to false, dated to false, dated to true), calls)
        loader.reset()
        loader.refresh(true)
        assertTrue(loader.sections.value.isEmpty())
        assertEquals(3, calls.size)
    }

    @Test fun refreshKeepsCardsButChangingDateCannotReuseThemOnFailure() = runBlocking {
        var access = SourceHomeGroupAccess(group = SourceHomeGroup("test", "Home", emptyList()))
        var gate: CompletableDeferred<Unit>? = null
        var calls = 0
        val page = SourceHomePage(emptyList(), false, "First day")
        val repository = object : SourceHomeGroupRepository {
            override fun observe(access: SourceHomeGroupAccess, request: SourceHomeRequest, refresh: Boolean) = flow {
                calls++
                emit(SectionState())
                gate?.await()
                emit(
                    if (request.date ==
                        null
                    ) {
                        SectionState(page, loading = false)
                    } else {
                        SectionState(loading = false, error = "offline")
                    },
                )
            }
        }
        val loader =
            SourceHomeFeedLoader(CoroutineScope(coroutineContext + Dispatchers.Unconfined), repository) { access }
        loader.load(SourceHomeRequest("schedule"))
        gate = CompletableDeferred()
        loader.refresh(true)
        assertEquals(page, loader.sections.value["schedule"]!!.data)
        loader.refresh(false)
        assertEquals(2, calls)
        gate!!.complete(Unit)
        loader.load(SourceHomeRequest("schedule", date = "2026-09-12"))
        assertNull(loader.sections.value["schedule"]!!.data)
        assertEquals("offline", loader.sections.value["schedule"]!!.error)
        access = access.copy(offline = true)
        loader.refresh(true)
        assertEquals(3, calls)
        loader.reset()
    }
}

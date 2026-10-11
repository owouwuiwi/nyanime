package it.lorelore.nyanime.studio

import it.lorelore.nyanime.novel.source.NovelPreparationLocks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.Closeable

@OptIn(ExperimentalCoroutinesApi::class)
class StudioNovelIndexTest {
    @Test
    fun chapterIndexesNeverPersistBodiesOrUnrelatedOutputFields() = runTest {
        val journal = MemoryJournal()
        val index =
            StudioNovelIndex(
                journal,
                { _, _ ->
                    buildJsonObject {
                        put(
                            "items",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("url", "chapter:one")
                                        put("title", "One")
                                        put("body", "private chapter body")
                                        put("unused", "private extra")
                                    },
                                ),
                            ),
                        )
                    }
                },
                { "access" },
                backgroundScope,
                { 1000 },
                StandardTestDispatcher(testScheduler),
            )
        val result = index.chapters(source, "book:one")
        assertEquals(setOf("url", "title"), result.single().keys)
        assertEquals(result, journal.entries(requireNotNull(journal.onlySaved().ready).id))
    }

    @Test
    fun anInvalidChapterIsRejectedBeforePublishingTheIndex() = runTest {
        val journal = MemoryJournal()
        val index =
            StudioNovelIndex(
                journal,
                { _, _ -> page("x".repeat(2049)) },
                { "access" },
                backgroundScope,
                { 1000 },
                StandardTestDispatcher(testScheduler),
            )
        assertTrue(runCatching { index.chapters(source, "book:one") }.isFailure)
        assertEquals(null, journal.onlySaved().ready)
        assertEquals(0, journal.onlySaved().staging?.found)
    }

    @Test
    fun aFreshReadyEditionDoesNotRepeatTheRemoteScan() = runTest {
        var calls = 0
        val index =
            StudioNovelIndex(
                MemoryJournal(),
                { _, _ ->
                    calls++
                    page("One")
                },
                { "access" },
                backgroundScope,
                { 1000 },
                StandardTestDispatcher(testScheduler),
            )
        val first = index.chapters(source, "book:one")
        val second = index.chapters(source, "book:one")
        assertEquals(first, second)
        assertEquals(1, calls)
    }

    private val source =
        StudioSource(
            uuid = "ce0e3a38-1ae8-4c57-8e6c-17dbf3000001",
            name = "Synthetic chapters",
            medium = StudioMedium.NOVEL,
            baseUrl = "https://source.invalid",
        )

    private fun page(vararg names: String, cursor: String? = null) = buildJsonObject {
        put(
            "items",
            JsonArray(
                names.map { name ->
                    buildJsonObject {
                        put("url", "chapter:" + name)
                        put("title", name)
                    }
                },
            ),
        )
        cursor?.let { put("cursor", it) }
        put("hasNext", cursor != null)
    }

    @Test
    fun concurrentCallersShareOneScanAndCancellingOneCallerDoesNotCancelTheOther() = runTest {
        val response = CompletableDeferred<Unit>()
        var calls = 0
        val journal = MemoryJournal()
        val index =
            StudioNovelIndex(
                journal,
                { _, _ ->
                    calls++
                    response.await()
                    page("One")
                },
                { "access" },
                backgroundScope,
                { 1000 },
                StandardTestDispatcher(testScheduler),
            )
        val first = async { index.chapters(source, "book:one") }
        val second = async { index.chapters(source, "book:one") }
        runCurrent()
        assertEquals(1, calls)
        first.cancelAndJoin()
        assertFalse(second.isCompleted)
        response.complete(Unit)
        assertEquals("One", second.await().single()["title"]?.studioText())
        assertEquals(1, calls)
    }

    @Test
    fun anUnrelatedTitleDoesNotWaitForTheFirstTitlesNetworkRequest() = runTest {
        val firstEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val index =
            StudioNovelIndex(
                MemoryJournal(),
                { request, _ ->
                    if (request.reference == "book:one") {
                        firstEntered.complete(Unit)
                        release.await()
                    }
                    page(request.reference)
                },
                { "access" },
                backgroundScope,
                { 1000 },
                StandardTestDispatcher(testScheduler),
            )
        val first = async { index.chapters(source, "book:one") }
        firstEntered.await()
        val second = async { index.chapters(source, "book:two") }
        assertEquals(1, second.await().size)
        assertFalse(first.isCompleted)
        release.complete(Unit)
        first.await()
    }

    @Test
    fun pauseAndResumeKeepCommittedPagesAndTheirCursor() = runTest {
        val calls = mutableListOf<String?>()
        var allowFinish = false
        val journal = MemoryJournal()
        val index =
            StudioNovelIndex(
                journal,
                { _, cursor ->
                    calls += cursor
                    if (cursor == null) {
                        page("One", cursor = "next")
                    } else if (allowFinish) {
                        page("Two")
                    } else {
                        awaitCancellation()
                    }
                },
                { "access" },
                backgroundScope,
                { 1000 },
                StandardTestDispatcher(testScheduler),
            )
        val first = async { index.chapters(source, "book:one") }
        runCurrent()
        assertEquals(listOf(null, "next"), calls)
        index.pause(StudioIndexRequest(source, "book:one", false, false))
        first.cancelAndJoin()
        assertEquals(1, journal.onlySaved().staging?.found)
        assertEquals(null, journal.onlySaved().ready)
        allowFinish = true
        val resumed = async { index.chapters(source, "book:one") }
        assertEquals(listOf("One", "Two"), resumed.await().map { it["title"]?.studioText() })
        assertEquals(listOf(null, "next", "next"), calls)
    }

    @Test
    fun failureDuringRefreshNeverPublishesAPartialListOrDeletesThePreviousReadyEdition() = runTest {
        var refresh = false
        val journal = MemoryJournal()
        val index =
            StudioNovelIndex(
                journal,
                { _, cursor ->
                    if (!refresh) {
                        page("Saved")
                    } else if (cursor == null) {
                        page("New", cursor = "next")
                    } else {
                        error("synthetic failure")
                    }
                },
                { "access" },
                backgroundScope,
                { 1000 },
                StandardTestDispatcher(testScheduler),
            )
        val initial = async { index.chapters(source, "book:one") }.await()
        refresh = true
        val error = runCatching {
            index.chapters(source, "book:one", force = true)
        }
            .exceptionOrNull()
        assertTrue(error != null)
        val saved = journal.onlySaved()
        assertEquals(initial, journal.entries(requireNotNull(saved.ready).id))
        assertEquals(1, saved.staging?.found)
    }

    @Test
    fun revokedAccessRejectsALatePageBeforeItCanBeSaved() = runTest {
        var granted = true
        val response = CompletableDeferred<Unit>()
        val journal = MemoryJournal()
        val index =
            StudioNovelIndex(
                journal,
                { _, _ ->
                    response.await()
                    page("Late")
                },
                {
                    check(granted)
                    "access"
                },
                backgroundScope,
                { 1000 },
                StandardTestDispatcher(testScheduler),
            )
        supervisorScope {
            val waiting = async { index.chapters(source, "book:one") }
            runCurrent()
            granted = false
            response.complete(Unit)
            assertTrue(runCatching { waiting.await() }.isFailure)
        }
        assertEquals(0, journal.onlySaved().staging?.found)
        assertEquals(null, journal.onlySaved().ready)
    }

    @Test
    fun previewAndActiveScopesDoNotShareTheirCheckpointOrPermission() = runTest {
        var calls = 0
        val journal = MemoryJournal()
        val index =
            StudioNovelIndex(
                journal,
                { _, _ ->
                    calls++
                    page("Chapter")
                },
                { "access" },
                backgroundScope,
                { 1000 },
                StandardTestDispatcher(testScheduler),
            )
        async { index.chapters(source, "book:one") }.await()
        async { index.chapters(source, "book:one", draftPreview = true) }.await()
        assertEquals(2, calls)
        assertEquals(2, journal.saved.size)
    }

    @Test
    fun anIncompletePageWithoutACursorFailsBeforeSavingAnyRows() = runTest {
        val journal = MemoryJournal()
        val index =
            StudioNovelIndex(
                journal,
                { _, _ ->
                    buildJsonObject {
                        put("items", JsonArray(emptyList()))
                        put("hasNext", true)
                    }
                },
                { "access" },
                backgroundScope,
                { 1000 },
                StandardTestDispatcher(testScheduler),
            )
        assertTrue(runCatching { index.chapters(source, "book:one") }.isFailure)
        assertEquals(0, journal.onlySaved().staging?.pages)
        assertEquals(null, journal.onlySaved().ready)
    }

    @Test
    fun keyedLocksReleaseCancelledWaitersAndAllowIndependentKeys() = runTest {
        val locks = NovelPreparationLocks()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            locks.withKey("one") {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val waiter = async { locks.withKey("one") { error("must not enter") } }
        runCurrent()
        waiter.cancelAndJoin()
        assertEquals("independent", locks.withKey("two") { "independent" })
        assertEquals(1, locks.retainedKeys())
        release.complete(Unit)
        first.await()
        assertEquals(0, locks.retainedKeys())
    }

    /** Records observable transactions, leaving actual SQLite atomicity to the Android probe. */
    private class MemoryJournal : StudioNovelIndexJournal {
        val saved = linkedMapOf<String, StudioIndexSaved>()
        private val data = mutableMapOf<Long, List<JsonObject>>()
        private var nextId = 0L

        fun onlySaved() = saved.values.single()

        override fun pin(key: String) = Closeable {}

        override fun read(key: String) = saved[key] ?: StudioIndexSaved()

        override fun begin(key: String, now: Long): StudioIndexGeneration {
            val existing = read(key)
            if (existing.staging != null) return existing.staging
            val stage = StudioIndexGeneration(++nextId, null, 0, 0, 0, 0, now)
            saved[key] = existing.copy(staging = stage)
            data[stage.id] = emptyList()
            return stage
        }

        override fun append(
            key: String,
            generation: Long,
            page: StudioIndexPage,
            now: Long,
        ): StudioIndexSaved {
            val before = read(key)
            val stage = requireNotNull(before.staging)
            check(stage.id == generation)
            val rows = (data[generation].orEmpty() + page.entries).distinctBy { it["url"] }
            data[generation] = rows
            val updated =
                stage.copy(
                    cursor = page.nextCursor,
                    pages = stage.pages + 1,
                    found = rows.size,
                    checked = stage.checked + page.entries.size,
                    updatedAt = now,
                )
            return (
                if (page.complete) {
                    StudioIndexSaved(ready = updated)
                } else {
                    before.copy(staging = updated)
                }
                )
                .also { saved[key] = it }
        }

        override fun entries(generation: Long) = data[generation].orEmpty()
    }
}

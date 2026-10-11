package it.lorelore.nyanime.novel

import it.lorelore.nyanime.novel.source.NovelChapterPreparation
import it.lorelore.nyanime.novel.source.NovelPreparationProgress
import it.lorelore.nyanime.novel.source.NovelSourceRegistration
import it.lorelore.nyanime.novel.source.requestNovelMetadata
import it.lorelore.nyanime.source.novel.NovelChapter
import it.lorelore.nyanime.source.novel.NovelDetails
import it.lorelore.nyanime.source.novel.NovelEntry
import it.lorelore.nyanime.source.novel.NovelSearchRequest
import it.lorelore.nyanime.source.novel.NovelSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NovelMetadataRequestTest {
    private open class Source : NovelSource {
        override val id = 901L
        override val name = "Synthetic"
        override val lang = "all"
        override suspend fun details(reference: String): NovelDetails {
            delay(40_000)
            return NovelDetails(NovelEntry(reference, "Title"))
        }
        override suspend fun chapters(reference: String): List<NovelChapter> {
            delay(6_000)
            return listOf(NovelChapter("chapter:one", "One"))
        }
        override suspend fun search(request: NovelSearchRequest) = error("unused")
        override suspend fun content(chapterReference: String) = error("unused")
    }

    @Test
    fun ordinaryCallsShareTheOriginal45SecondBudget() = runTest {
        val source = Source()
        val registration = NovelSourceRegistration(source, "synthetic", "one")
        val result = runCatching { requestNovelMetadata(source.id, "book:one", { registration }) }
        assertTrue(result.exceptionOrNull() is TimeoutCancellationException)
        assertEquals(45_000L, currentTime)
    }

    @Test
    fun onlyTheExplicitPreparationCapabilitySeparatesItsIndexFromThatBudget() = runTest {
        val source = object : Source(), NovelChapterPreparation {
            override fun observePreparation(reference: String) = flowOf(NovelPreparationProgress())
            override suspend fun pausePreparation(reference: String) {}
            override suspend fun preparedChapters(reference: String, force: Boolean): List<NovelChapter> {
                delay(60_000)
                return listOf(NovelChapter("chapter:one", "One"))
            }
            override suspend fun chapters(reference: String) = error("Must use explicit preparation")
        }
        val registration = NovelSourceRegistration(source, "synthetic", "one")
        val result = requestNovelMetadata(source.id, "book:one", { registration })
        assertEquals(null, result.ordinaryChapters)
        assertEquals(40_000L, currentTime)
        assertEquals(1, source.preparedChapters("book:one").size)
        assertEquals(100_000L, currentTime)
    }

    @Test
    fun aChangedProviderRejectsMetadataBeforeTheUiCallback() = runTest {
        val source = Source()
        var calls = 0
        var displayed = false
        val result = runCatching {
            requestNovelMetadata(
                source.id,
                "book:one",
                { NovelSourceRegistration(source, "synthetic", if (++calls == 1) "old" else "new") },
                { displayed = true },
            )
        }
        assertTrue(result.isFailure)
        assertTrue(!displayed)
        assertEquals(40_000L, currentTime)
    }
}

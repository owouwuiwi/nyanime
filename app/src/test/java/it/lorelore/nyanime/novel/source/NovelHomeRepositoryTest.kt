package it.lorelore.nyanime.novel.source

import it.lorelore.nyanime.source.novel.NovelChapter
import it.lorelore.nyanime.source.novel.NovelContent
import it.lorelore.nyanime.source.novel.NovelDetails
import it.lorelore.nyanime.source.novel.NovelEntry
import it.lorelore.nyanime.source.novel.NovelHomeRequest
import it.lorelore.nyanime.source.novel.NovelHomeSection
import it.lorelore.nyanime.source.novel.NovelHomeSource
import it.lorelore.nyanime.source.novel.NovelSearchPage
import it.lorelore.nyanime.source.novel.NovelSearchRequest
import it.lorelore.nyanime.source.novel.NovelSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NovelHomeRepositoryTest {
    private class Source : NovelSource, NovelHomeSource {
        override val id = 73L
        override val name = "Fictional source"
        override val lang = "all"
        override val homeSections = listOf(NovelHomeSection("books", "Books"))
        var calls = 0
        var wait: CompletableDeferred<Unit>? = null
        var started = CompletableDeferred<Unit>()

        override suspend fun home(request: NovelHomeRequest): NovelSearchPage {
            calls++
            started.complete(Unit)
            wait?.await()
            return NovelSearchPage(
                (1..40).map { NovelEntry("book:$it", "Book $it") } +
                    NovelEntry("book:1", "Duplicate"),
            )
        }

        override suspend fun search(request: NovelSearchRequest): NovelSearchPage =
            error("Home must not search")

        override suspend fun details(reference: String): NovelDetails =
            error("Home must not load details")

        override suspend fun chapters(reference: String): List<NovelChapter> =
            error("Home must not index chapters")

        override suspend fun content(chapterReference: String): NovelContent =
            error("Home must not read bodies")
    }

    @Test
    fun `Home is bounded cached and refreshed without search or chapter side effects`() =
        runBlocking {
            val source = Source()
            val registration = NovelSourceRegistration(source, "fictional", "one")
            var clock = 0L
            val repository = NovelHomeRepository({ registration }, { clock })
            val section = source.homeSections.single()
            assertEquals(24, repository.load(registration, section).items.size)
            assertEquals(
                24,
                repository.load(registration, section).items.map { it.reference }.distinct().size,
            )
            assertEquals(1, source.calls)
            clock = 300_001
            repository.load(registration, section)
            assertEquals(2, source.calls)
            repository.load(registration, section, refresh = true)
            assertEquals(3, source.calls)
            repository.clear()
            repository.load(registration, section)
            assertEquals(4, source.calls)
        }

    @Test
    fun `changed revisions cannot read a cached or late result`() {
        val source = Source()
        val registration = NovelSourceRegistration(source, "fictional", "one")
        var current = registration
        val repository = NovelHomeRepository({ current })
        runBlocking { repository.load(registration, source.homeSections.single()) }
        current = registration.copy(revision = "two")
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.load(registration, source.homeSections.single()) }
        }
        current = registration
        source.wait = CompletableDeferred()
        source.started = CompletableDeferred()
        runBlocking {
            val response = async {
                runCatching {
                    repository.load(registration, source.homeSections.single(), refresh = true)
                }
            }
            source.started.await()
            current = registration.copy(revision = "three")
            source.wait!!.complete(Unit)
            assertEquals(true, response.await().isFailure)
        }
    }

    @Test
    fun `an undeclared section is rejected without executing provider discovery`() {
        val source = Source()
        val registration = NovelSourceRegistration(source, "fictional", "one")
        val repository = NovelHomeRepository({ registration })
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.load(registration, NovelHomeSection("unknown", "Unknown")) }
        }
        assertEquals(0, source.calls)
    }
}

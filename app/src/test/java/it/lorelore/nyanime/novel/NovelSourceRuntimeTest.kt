package it.lorelore.nyanime.novel

import it.lorelore.nyanime.novel.source.NovelSourceRegistration
import it.lorelore.nyanime.novel.source.NovelSourceRuntime
import it.lorelore.nyanime.source.novel.NovelChapter
import it.lorelore.nyanime.source.novel.NovelContent
import it.lorelore.nyanime.source.novel.NovelDetails
import it.lorelore.nyanime.source.novel.NovelEntry
import it.lorelore.nyanime.source.novel.NovelResourceReference
import it.lorelore.nyanime.source.novel.NovelSearchPage
import it.lorelore.nyanime.source.novel.NovelSearchRequest
import it.lorelore.nyanime.source.novel.NovelSource
import it.lorelore.nyanime.source.novel.NovelTextFormat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NovelSourceRuntimeTest {
    private class Source : NovelSource {
        override val id = 17L
        override val name = "Fictional"
        override val lang = "all"
        var body = NovelContent("chapter:one", "One", "Readable", NovelTextFormat.TEXT)
        var requests = 0

        override suspend fun search(request: NovelSearchRequest) = NovelSearchPage(emptyList())

        override suspend fun details(reference: String) =
            NovelDetails(NovelEntry(reference, "Work"))

        override suspend fun chapters(reference: String) =
            listOf(NovelChapter("chapter:one", "One"))

        override suspend fun content(chapterReference: String): NovelContent {
            requests++
            return body
        }
    }

    @Test
    fun `source declared text and images cross the optional adapter without name inference`() =
        runBlocking<Unit> {
            val source = Source()
            source.body =
                source.body.copy(
                    resources = listOf(NovelResourceReference("relative/image", "opaque:image")),
                )
            val registration = NovelSourceRegistration(source, "fictional.package", "revision")
            val runtime =
                NovelSourceRuntime(registration, { registration }, { error("No images requested") })
            val body = runtime.chapter("chapter:one")
            assertEquals("Readable", body.body)
            assertEquals(false, body.html)
            assertEquals(mapOf("relative/image" to "opaque:image"), body.resources)
            assertEquals(1, source.requests)
        }

    @Test
    fun `a source update during an operation rejects the late answer`() {
        val source = Source()
        val registration = NovelSourceRegistration(source, "fictional.package", "old")
        var resolutions = 0
        val runtime =
            NovelSourceRuntime(
                registration,
                {
                    registration.copy(revision = if (++resolutions == 1) "old" else "new")
                },
                { error("No images requested") },
            )
        assertThrows(IllegalStateException::class.java) {
            runBlocking { runtime.chapter("chapter:one") }
        }
        assertEquals(1, source.requests)
    }

    @Test
    fun `mismatching identities and conflicting image mappings are rejected`() {
        val source = Source()
        val registration = NovelSourceRegistration(source, "fictional.package", "revision")
        val runtime =
            NovelSourceRuntime(registration, { registration }, { error("No images requested") })
        source.body = source.body.copy(reference = "another")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { runtime.chapter("chapter:one") }
        }
        source.body =
            source.body.copy(
                reference = "chapter:one",
                resources =
                listOf(
                    NovelResourceReference("image", "one"),
                    NovelResourceReference("image", "two"),
                ),
            )
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { runtime.chapter("chapter:one") }
        }
    }
}

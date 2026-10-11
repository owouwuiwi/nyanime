package it.lorelore.nyanime.novel

import it.lorelore.nyanime.novel.source.NovelSourceInventories
import it.lorelore.nyanime.novel.source.NovelSourceInventory
import it.lorelore.nyanime.novel.source.NovelSourceRegistration
import it.lorelore.nyanime.source.novel.NovelChapter
import it.lorelore.nyanime.source.novel.NovelContent
import it.lorelore.nyanime.source.novel.NovelDetails
import it.lorelore.nyanime.source.novel.NovelEntry
import it.lorelore.nyanime.source.novel.NovelSearchPage
import it.lorelore.nyanime.source.novel.NovelSearchRequest
import it.lorelore.nyanime.source.novel.NovelSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovelSourceInventoriesTest {
    private class Source(
        override val id: Long,
        override val lang: String = "all",
        override val isNsfw: Boolean = false,
    ) : NovelSource {
        override val name = "Synthetic source"

        override suspend fun search(request: NovelSearchRequest) = NovelSearchPage(emptyList())

        override suspend fun details(reference: String) =
            NovelDetails(NovelEntry(reference, "Synthetic work"))

        override suspend fun chapters(reference: String) = emptyList<NovelChapter>()

        override suspend fun content(chapterReference: String) =
            NovelContent(chapterReference, "Synthetic chapter", "Text")
    }

    private fun registration(id: Long, lang: String = "all", adult: Boolean = false) =
        NovelSourceRegistration(Source(id, lang, adult), "source.$id", "revision")

    @Test
    fun `registries retain provider provenance without trusting a registration supplied provider id`() {
        val apk = registration(1).copy(providerId = "forged")
        val result =
            NovelSourceInventories.merge(
                listOf(
                    "apk" to NovelSourceInventory(listOf(apk), initialized = true),
                    "studio" to NovelSourceInventory(listOf(registration(2)), initialized = true),
                ),
                setOf("it"),
                false,
            )
        assertEquals(listOf("apk", "studio"), result.sources.map { it.providerId })
        assertTrue(result.initialized)
    }

    @Test
    fun `colliding exact source ids are unavailable across registries rather than merged or replaced`() {
        val result =
            NovelSourceInventories.merge(
                listOf(
                    "apk" to NovelSourceInventory(listOf(registration(1)), initialized = true),
                    "studio" to
                        NovelSourceInventory(
                            listOf(
                                registration(1).copy(packageName = "studio.one"),
                                registration(2),
                            ),
                            initialized = true,
                        ),
                ),
                setOf("it"),
                false,
            )
        assertEquals(listOf(2L), result.sources.map { it.source.id })
        assertEquals(setOf("source.1", "studio.one"), result.unavailable)
    }

    @Test
    fun `languages adult preference and incomplete inventory never grant extra capabilities`() {
        val result =
            NovelSourceInventories.merge(
                listOf(
                    "apk" to
                        NovelSourceInventory(
                            listOf(
                                registration(1, "en"),
                                registration(2, "it"),
                                registration(3, adult = true),
                            ),
                            unavailable = setOf("failed.package"),
                            initialized = true,
                        ),
                    "studio" to NovelSourceInventory(),
                ),
                setOf("it"),
                false,
            )
        assertEquals(listOf(2L), result.sources.map { it.source.id })
        assertEquals(setOf("failed.package"), result.unavailable)
        assertFalse(result.initialized)
    }
}

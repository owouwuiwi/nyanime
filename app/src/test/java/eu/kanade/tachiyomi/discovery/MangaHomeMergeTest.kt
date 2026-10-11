package eu.kanade.tachiyomi.discovery

import eu.kanade.tachiyomi.data.discovery.MangaHomeItem
import eu.kanade.tachiyomi.data.discovery.MangaHomeMerge
import eu.kanade.tachiyomi.data.discovery.MangaHomePage
import eu.kanade.tachiyomi.data.discovery.MangaHomePresentation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.entries.manga.model.Manga

class MangaHomeMergeTest {
    @Test
    fun `different titles merge only when their shared catalogue id agrees`() {
        val first = item(1, "The First Title", mapOf("anilist" to 41))
        val translated = item(2, "Un titolo diverso", mapOf("anilist" to 41))
        val unrelated = item(3, "The First Title", mapOf("anilist" to 42))

        val result = MangaHomeMerge.merge(
            listOf(MangaHomePage(listOf(first), false), MangaHomePage(listOf(translated, unrelated), false)),
        )

        assertEquals(2, result.items.size)
        assertEquals(listOf(2L), result.items.first().alternateSources.map { it.source })
        assertEquals(3L, result.items.last().manga.source)
    }

    @Test
    fun `identical names without ids remain separate`() {
        val result = MangaHomeMerge.merge(
            listOf(
                MangaHomePage(listOf(item(1, "Same", emptyMap())), false),
                MangaHomePage(listOf(item(2, "Same", emptyMap())), false),
            ),
        )
        assertEquals(2, result.items.size)
    }

    @Test
    fun `conflicting public ids never merge even if one id agrees`() {
        val first = item(1, "Same", mapOf("anilist" to 41, "myanimelist" to 90))
        val other = item(2, "Same", mapOf("anilist" to 41, "myanimelist" to 91))

        val result = MangaHomeMerge.merge(
            listOf(
                MangaHomePage(listOf(first), false),
                MangaHomePage(listOf(other), false),
            ),
        )

        assertEquals(2, result.items.size)
    }

    private fun item(source: Long, title: String, ids: Map<String, Long>) = MangaHomeItem(
        manga = Manga.create().copy(id = source, source = source, url = "/$source", title = title),
        presentation = MangaHomePresentation(catalogIds = ids),
    )
}

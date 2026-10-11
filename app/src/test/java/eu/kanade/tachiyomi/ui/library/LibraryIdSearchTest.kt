package eu.kanade.tachiyomi.ui.library

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.ui.library.anime.AnimeLibraryItem
import eu.kanade.tachiyomi.ui.library.manga.MangaLibraryItem
import kotlinx.coroutines.flow.Flow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.library.anime.LibraryAnime
import tachiyomi.domain.library.manga.LibraryManga
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.source.manga.service.MangaSourceManager

class LibraryIdSearchTest {
    private val anime = AnimeLibraryItem(
        LibraryAnime(Anime.create().copy(id = 42), 0, 0, 0, 0, 0, 0, 0, 0),
        sourceManager = object : AnimeSourceManager {
            override val isInitialized: Nothing get() = unexpectedSourceAccess()
            override val sources: Nothing get() = unexpectedSourceAccess()
            override fun get(sourceKey: Long): Nothing = unexpectedSourceAccess()
            override fun getOrStub(sourceKey: Long): Nothing = unexpectedSourceAccess()
            override fun getAll(): Nothing = unexpectedSourceAccess()
            override fun getOnlineSources(): Nothing = unexpectedSourceAccess()
            override fun getStubSources(): Nothing = unexpectedSourceAccess()
        },
    )
    private val manga = MangaLibraryItem(
        LibraryManga(Manga.create().copy(id = 42), 0, 0, 0, 0, 0, 0, 0),
        sourceManager = object : MangaSourceManager {
            override val isInitialized: Nothing get() = unexpectedSourceAccess()
            override val catalogueSources: Flow<List<CatalogueSource>> get() = unexpectedSourceAccess()
            override fun get(sourceKey: Long): Nothing = unexpectedSourceAccess()
            override fun getOrStub(sourceKey: Long): Nothing = unexpectedSourceAccess()
            override fun getOnlineSources(): Nothing = unexpectedSourceAccess()
            override fun getCatalogueSources(): List<CatalogueSource> = unexpectedSourceAccess()
            override fun getStubSources(): Nothing = unexpectedSourceAccess()
        },
    )

    @Test
    fun `anime ID prefix accepts every letter case without consulting a source`() {
        for (prefix in listOf("id:", "Id:", "iD:", "ID:")) {
            assertTrue(anime.matches(prefix + "42"), prefix)
            assertFalse(anime.matches(prefix + "41"), prefix)
        }
    }

    @Test
    fun `manga ID prefix accepts every letter case without consulting a source`() {
        for (prefix in listOf("id:", "Id:", "iD:", "ID:")) {
            assertTrue(manga.matches(prefix + "42"), prefix)
            assertFalse(manga.matches(prefix + "41"), prefix)
        }
    }

    @Test
    fun `invalid and overflowing IDs match neither medium and do not fall back to title search`() {
        for (query in listOf("id:", "ID:", "ID:invalid", "Id:9223372036854775808", "id:42,other")) {
            assertFalse(anime.matches(query), query)
            assertFalse(manga.matches(query), query)
        }
    }

    private fun unexpectedSourceAccess(): Nothing = error("An identity lookup must not call the source manager")
}

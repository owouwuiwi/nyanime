package eu.kanade.tachiyomi.data.share

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.Base64

class ContentLinksTest {
    @Test
    fun `live links contain opaque channel references without episode progress or streams`() {
        val channel =
            ContentLink(medium = SharedMedium.TV, sourceId = 42, entryUrl = "opaque-feed", title = "Test Channel")
        assertEquals(channel, ContentLinks.decode(ContentLinks.encode(channel)))
        assertThrows(IllegalArgumentException::class.java) { ContentLinks.encode(channel.copy(positionMs = 123)) }
        assertThrows(IllegalArgumentException::class.java) {
            ContentLinks.encode(channel.copy(itemUrl = "opaque-item"))
        }
        assertThrows(IllegalArgumentException::class.java) { ContentLinks.encode(channel.copy(page = 1)) }
        assertThrows(IllegalArgumentException::class.java) {
            ContentLinks.encode(channel.copy(entryUrl = "https://example.invalid/live.m3u8"))
        }
    }
    private val anime = ContentLink(
        medium = SharedMedium.ANIME,
        sourceId = Long.MIN_VALUE + 17,
        entryUrl = "/catalogue/title?edition=2&lang=en",
        title = "Titolo di prova — seconda parte",
        sourceName = "TestSource",
    )
    private val manga = anime.copy(medium = SharedMedium.MANGA, sourceId = Long.MAX_VALUE)

    @Test
    fun `all six targets round trip without database IDs`() {
        val cases = listOf(
            anime,
            anime.copy(itemUrl = "/episode/3", itemTitle = "Episode 3").itemFromStart(),
            anime.copy(itemUrl = "/episode/3", itemTitle = "Episode 3", positionMs = 123_456),
            manga,
            manga.copy(itemUrl = "/chapter/2", itemTitle = "Chapter 2").itemFromStart(),
            manga.copy(itemUrl = "/chapter/2", itemTitle = "Chapter 2", page = 17),
        )
        cases.forEach { assertEquals(it, ContentLinks.decode(ContentLinks.encode(it))) }
    }

    @Test
    fun `source references and unicode remain opaque`() {
        val link = manga.copy(
            entryUrl = "/entry/%E6%9C%AC?variant=a+b#part",
            title = "本 · Titolo à è",
            itemUrl = "/c/2?x=1&y=2",
        )
        assertEquals(link, ContentLinks.decode(ContentLinks.encode(link)))
        assertFalse(ContentLinks.encode(link).contains(" "))
    }

    @Test
    fun `shared text returns the same link and rejects ambiguous messages`() {
        val encoded = ContentLinks.encode(anime)
        assertEquals(anime, ContentLinks.decode("Titolo di prova\n$encoded\nApri con Nyanime"))
        assertNull(ContentLinks.decode("$encoded\n$encoded"))
        assertNull(ContentLinks.extract("nyanime://watch/v1#NY1.test"))
    }

    @Test
    fun `unsupported routes and malformed encoding are rejected`() {
        val encoded = raw(Json.encodeToString(anime))
        listOf(
            encoded.replace("/v1#", "/v2#"),
            encoded.replace("/v1#", "/v1?key=1#"),
            encoded.replace("open/", "open:443/"),
            encoded.replace("open/", "user@open/"),
            "${ContentLinks.PREFIX}%%%",
            "${ContentLinks.PREFIX}A",
            "${ContentLinks.PREFIX}___",
            ContentLinks.PREFIX,
        ).forEach { assertNull(ContentLinks.decode(it), it.take(60)) }
        assertNull(ContentLinks.decode(ContentLinks.PREFIX + "a".repeat(ContentLinks.MAX_LINK_LENGTH)))
    }

    @Test
    fun `clickable readable links keep references exact and use a display only slug`() {
        val encoded = ContentLinks.encode(anime)
        assertTrue(encoded.startsWith(ContentLinks.WEB_PREFIX + "v2/anime/titolo-di-prova-seconda-parte?"))
        assertTrue(encoded.contains("ref=/catalogue/title%3Fedition%3D2%26lang%3Den"))
        assertEquals(anime, ContentLinks.decode(encoded.replace("titolo-di-prova-seconda-parte?", "another-title?")))
        assertEquals(anime, ContentLinks.decode(raw(Json.encodeToString(anime))))
        assertEquals(
            anime,
            ContentLinks.decode(encoded.replace(ContentLinks.WEB_PREFIX, "nyanime://open/v2#")),
        )
    }

    @Test
    fun `spoofed web origins ambiguous fields and invalid numeric or utf8 values are rejected`() {
        val link = anime.copy(itemUrl = "/episode/1", positionMs = 42_000)
        val encoded = ContentLinks.encode(link)
        listOf(
            encoded.replace("https://", "http://"),
            encoded.replace("owouwuiwi.github.io", "owouwuiwi.github.io.example.org"),
            encoded.replace("owouwuiwi.github.io", "user@owouwuiwi.github.io"),
            encoded.replace("owouwuiwi.github.io", "owouwuiwi.github.io:443"),
            encoded.replace("/open/#", "/open/?key=1#"),
            encoded.replace("v2/anime/", "v3/anime/"),
            encoded.replace("v2/anime/", "v2/unknown/"),
            encoded + "&source=23",
            encoded + "&%73ource=23",
            encoded.replace("at=42000", "at=not-a-number"),
            encoded.replace("title=Titolo", "title=%FFTitolo"),
            encoded.replace("title=Titolo", "title=%00Titolo"),
        ).forEach { assertNull(ContentLinks.decode(it), it.take(100)) }
        assertNull(ContentLinks.decode(ContentLinks.encode(manga.copy(itemUrl = "/c/1", page = 2)) + "&at=0"))
    }

    @Test
    fun `new organization links and previously shared links resolve the same exact target`() {
        val cases = listOf(
            anime.copy(itemUrl = "/episode/2", positionMs = 125_000),
            manga.copy(itemUrl = "/chapter/3", page = 17),
        )
        cases.forEach { target ->
            val link = ContentLinks.encode(target)
            assertTrue(link.startsWith("https://owouwuiwi.github.io/open/#"))
            val legacy = link.replace("owouwuiwi.github.io", "noire342.github.io")
            assertEquals(target, ContentLinks.decode(legacy))
            assertEquals(target, ContentLinks.decode("Shared title\n$legacy"))
            assertNull(ContentLinks.decode(legacy.replace("noire342.github.io", "noire342.github.io.example.org")))
        }
    }

    @Test
    fun `invalid references cannot be emitted or accepted`() {
        val invalid = listOf(
            anime.copy(version = 2),
            anime.copy(sourceId = 0),
            anime.copy(entryUrl = ""),
            anime.copy(title = ""),
            anime.copy(title = "A\nB"),
            anime.copy(entryUrl = "x".repeat(4097)),
            anime.copy(itemTitle = "Episode 1"),
            anime.copy(positionMs = 0),
            anime.copy(itemUrl = "/1", positionMs = -1),
            anime.copy(itemUrl = "/1", positionMs = ContentLinks.MAX_POSITION_MS + 1),
            anime.copy(itemUrl = "/1", page = 1),
            manga.copy(itemUrl = "/1", positionMs = 1),
            manga.copy(itemUrl = "/1", page = 0),
            manga.copy(itemUrl = "/1", page = ContentLinks.MAX_PAGE + 1),
        )
        invalid.forEach {
            assertThrows(IllegalArgumentException::class.java) { ContentLinks.encode(it) }
            assertNull(ContentLinks.decode(raw(Json.encodeToString(it))))
        }
    }

    @Test
    fun `future cosmetic fields do not change versioned target resolution`() {
        val json = Json.encodeToString(anime).dropLast(1) + ",\"cosmetic\":\"future value\"}"
        assertEquals(anime, ContentLinks.decode(raw(json)))
        val unsupported = Json.encodeToString(anime).replace("\"ANIME\"", "\"UNKNOWN\"")
        assertNull(ContentLinks.decode(raw(unsupported)))
    }

    @Test
    fun `title and start choices never leak a previous position`() {
        val timed = anime.copy(itemUrl = "/1", itemTitle = "Episode 1", positionMs = 88_000)
        assertEquals(anime, timed.entryOnly())
        assertEquals(0L, timed.itemFromStart().positionMs)
        val paged = manga.copy(itemUrl = "/2", itemTitle = "Chapter 2", page = 20)
        assertEquals(manga, paged.entryOnly())
        assertEquals(1, paged.itemFromStart().page)
        assertEquals(manga, manga.itemFromStart())
    }

    @Test
    fun `emitted payload contains only the public content contract`() {
        val encoded = ContentLinks.encode(anime.copy(itemUrl = "/1", positionMs = 42_000))
        val uri = URI(encoded)
        assertNull(uri.rawQuery)
        val keys = uri.rawFragment.substringAfter('?').split('&').map { it.substringBefore('=') }.toSet()
        assertTrue(
            keys.all {
                it in setOf(
                    "source",
                    "sourceName",
                    "ref",
                    "title",
                    "item",
                    "itemTitle",
                    "at",
                    "page",
                )
            },
        )
        assertFalse(
            keys.any {
                it in setOf("animeId", "mangaId", "cookie", "headers", "videoUrl", "secret", "roomCode")
            },
        )
    }

    private fun raw(json: String): String = ContentLinks.PREFIX +
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(Charsets.UTF_8))
}

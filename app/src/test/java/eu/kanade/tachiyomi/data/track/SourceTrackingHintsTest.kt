package eu.kanade.tachiyomi.data.track

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaTrackingMetadata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SourceTrackingHintsTest {
    @Test
    fun acceptsOnlyPositiveCatalogIds() {
        val hints = SourceTrackingHints.parse(
            Json.parseToJsonElement(
                """{"ids":{"anilist":120,"myanimelist":34,"mangaupdates":-4},"titles":["Original title","Original title"]}""",
            ).jsonObject,
        )!!
        assertEquals(120L, hints.anilistId)
        assertEquals(34L, hints.malId)
        assertNull(hints.mangaUpdatesId)
        assertEquals(listOf("Original title"), hints.titles)
    }

    @Test
    fun ignoresEmptyMetadata() {
        assertNull(SourceTrackingHints.parse(Json.parseToJsonElement("""{"ids":{}}""").jsonObject))
        assertNull(SourceTrackingHints.parse(Json.parseToJsonElement("""{"ids":[],"titles":false}""").jsonObject))
    }

    @Test
    fun readsGenericAnimeAndMangaExtensionContracts() {
        val anime = SAnime.create().apply {
            memo = Json.parseToJsonElement("""{"nyanime.tracking.v1":{"ids":{"myanimelist":42}}}""").jsonObject
        }
        assertEquals(42L, SourceTrackingHints.from(anime)?.malId)

        val manga = SManga.create().also {
            (it as SMangaTrackingMetadata).trackingMetadata = """{"ids":{"anilist":73}}"""
        }
        assertEquals(73L, SourceTrackingHints.from(manga)?.anilistId)
    }
}

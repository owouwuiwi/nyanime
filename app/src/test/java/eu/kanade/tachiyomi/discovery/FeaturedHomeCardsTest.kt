package eu.kanade.tachiyomi.discovery

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.data.discovery.FeaturedHomeCards
import eu.kanade.tachiyomi.data.discovery.SourceHomeMetadata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.discovery.SourceHomePresentation
import tachiyomi.domain.discovery.homePresentation
import tachiyomi.domain.entries.anime.model.Anime

class FeaturedHomeCardsTest {
    private fun card(id: Long, source: Long = 1, catalog: Long? = null, episode: String? = null): Anime {
        val anime = Anime.create().copy(id = id, source = source, url = "/work/$id", title = "Example")
        return anime.copy(
            memo = SourceHomePresentation(
                id = episode,
                releaseYear = 2026,
                catalogIds = catalog?.let { mapOf("myanimelist" to it) }.orEmpty(),
            ).attachTo(anime.memo),
        )
    }

    @Test
    fun `episode cards sharing a concrete work appear once without changing the feed`() {
        val latest = card(1, episode = "episode:9")
        val previous = latest.copy(memo = SourceHomePresentation(id = "episode:8").attachTo(latest.memo))
        val input = listOf(latest, previous, card(2))
        val output = FeaturedHomeCards.items(input)
        assertEquals(listOf(1L, 2L), output.map { it.id })
        assertEquals("episode:9", output.first().homePresentation?.id)
        assertEquals(3, input.size)
        assertEquals("episode:8", input[1].homePresentation?.id)
    }

    @Test
    fun `verified copies of the same variant retain every openable alternative`() {
        val output = FeaturedHomeCards.items(listOf(card(1, catalog = 42), card(2, catalog = 42), card(3, 2, 42)))
        assertEquals(1, output.size)
        assertEquals(listOf(1L, 2L, 3L), output.single().homePresentation!!.choices.map { it.animeId })
    }

    @Test
    fun `different complete edition labels remain separate even with the same catalogue id`() {
        val original = card(1, catalog = 42)
        val edition = card(2, catalog = 42).copy(title = "Example (Edition B)")
        val originalCopy = card(3, 2, 42)
        val editionCopy = card(4, 2, 42).copy(title = edition.title)
        val output = FeaturedHomeCards.items(listOf(original, edition, originalCopy, editionCopy))
        assertEquals(2, output.size)
        assertEquals(listOf(1L, 3L), output[0].homePresentation!!.choices.map { it.animeId })
        assertEquals(listOf(2L, 4L), output[1].homePresentation!!.choices.map { it.animeId })
    }

    @Test
    fun `carousel selection follows the work when the source updates its episode card`() {
        val first = card(1, episode = "episode:8")
        val updated = first.copy(memo = SourceHomePresentation(id = "episode:9").attachTo(first.memo))
        assertEquals(FeaturedHomeCards.key(first), FeaturedHomeCards.key(updated))
        val edition = first.copy(title = "Example (Edition B)")
        org.junit.jupiter.api.Assertions.assertNotEquals(FeaturedHomeCards.key(first), FeaturedHomeCards.key(edition))
    }

    @Test
    fun `homonyms without ids and conflicting seasons are never hidden`() {
        assertEquals(2, FeaturedHomeCards.items(listOf(card(1), card(2, source = 2))).size)
        assertEquals(2, FeaturedHomeCards.items(listOf(card(1, catalog = 42), card(2, 2, 43))).size)
    }

    @Test
    fun `personal sources sharing a complete work address merge even after other providers are grouped`() {
        val first = SourceHomeMetadata.withWorkUrl(card(1, catalog = 42), "https://one.example/work/1")
        val second = SourceHomeMetadata.withWorkUrl(card(2, 2, 42), "https://two.example/work/2")
        val grouped = tachiyomi.data.discovery.mergeHomeCards(listOf(first, second), preserveTitleVariants = true)
        val personal = SourceHomeMetadata.withWorkUrl(card(3, 3), "https://two.example/work/2")
        val output = FeaturedHomeCards.items(grouped + personal)
        assertEquals(1, output.size)
        assertEquals(listOf(1L, 2L, 3L), output.single().homePresentation!!.choices.map { it.animeId })
        val edition = personal.copy(title = "Example (Edition B)")
        assertEquals(2, FeaturedHomeCards.items(output + edition).size)
    }

    @Test
    fun `relative paths homepages queries and conflicting ids never establish false URL matches`() {
        val first = SourceHomeMetadata.withWorkUrl(card(1), "https://one.example/work/1")
        val differentHost = SourceHomeMetadata.withWorkUrl(card(2, 2), "https://two.example/work/1")
        assertEquals(2, FeaturedHomeCards.items(listOf(first, differentHost)).size)
        for (url in listOf(
            "/work/1",
            "https://one.example/",
            "https://user:secret@one.example/work/1",
            "file:///work/1",
        )) {
            val a = SourceHomeMetadata.withWorkUrl(card(1), url)
            val b = SourceHomeMetadata.withWorkUrl(card(2, 2), url)
            assertNull(a.homePresentation!!.workUrl)
            assertEquals(2, FeaturedHomeCards.items(listOf(a, b)).size)
        }
        val queryA = SourceHomeMetadata.withWorkUrl(card(1), "https://one.example/?work=1")
        val queryB = SourceHomeMetadata.withWorkUrl(card(2, 2), "https://one.example/?work=2")
        assertEquals(2, FeaturedHomeCards.items(listOf(queryA, queryB)).size)
        val identified = SourceHomeMetadata.withWorkUrl(card(1, catalog = 42), "https://one.example/work/1")
        val conflict = SourceHomeMetadata.withWorkUrl(card(2, 2, 43), "https://one.example/work/1")
        assertEquals(2, FeaturedHomeCards.items(listOf(identified, conflict)).size)
    }

    @Test
    fun `tracking metadata already declared by a source is available to home merging`() {
        val remote = SAnime.create().apply {
            memo = Json.parseToJsonElement(
                """{"nyanime.tracking.v1":{"ids":{"myanimelist":42},"titles":["Alternative"]}}""",
            ).jsonObject
        }
        val presentation = SourceHomeMetadata.presentation(remote)!!
        assertEquals(mapOf("myanimelist" to 42L), presentation.catalogIds)
        assertEquals(listOf("Alternative"), presentation.aliases)
        val cached = card(1).copy(memo = remote.memo)
        val output = FeaturedHomeCards.items(listOf(cached, card(2, 2, 42)))
        assertEquals(1, output.size)
        assertEquals(listOf(1L, 2L), output.single().homePresentation!!.choices.map { it.animeId })
    }

    @Test
    fun `contradictory metadata cannot authorize a merge through another shared id or year`() {
        val remote = SAnime.create().apply {
            memo = Json.parseToJsonElement(
                """{"nyanime.tracking.v1":{"ids":{"myanimelist":99,"anilist":7}}}""",
            ).jsonObject
        }
        val home = SourceHomePresentation(catalogIds = mapOf("myanimelist" to 42), releaseYear = 2026)
        remote.memo = home.attachTo(remote.memo)
        val presentation = SourceHomeMetadata.presentation(remote)!!
        assertEquals(emptyMap<String, Long>(), presentation.catalogIds)
        assertNull(presentation.releaseYear)
        val cached = card(1).copy(memo = remote.memo)
        assertEquals(2, FeaturedHomeCards.items(listOf(cached, card(2, 2, 42))).size)
        val attached = cached.copy(memo = SourceHomeMetadata.attach(remote, cached.memo))
        val restored = SourceHomeMetadata.enrich(SourceHomeMetadata.enrich(attached))
        assertEquals(emptyMap<String, Long>(), restored.homePresentation!!.catalogIds)
        assertEquals(2, FeaturedHomeCards.items(listOf(restored, card(2, 2, 99))).size)

        // The disk Home cache stores the presentation, not transient memo markers.
        val diskMemo = restored.homePresentation!!.attachTo(
            JsonObject(mapOf("nyanime.tracking.v1" to remote.memo.getValue("nyanime.tracking.v1"))),
        )
        val afterRestart = SourceHomeMetadata.enrich(restored.copy(memo = diskMemo))
        assertEquals(true, afterRestart.homePresentation!!.identityConflict)
        assertEquals(emptyMap<String, Long>(), afterRestart.homePresentation!!.catalogIds)
        assertEquals(2, FeaturedHomeCards.items(listOf(afterRestart, card(2, 2, 99))).size)
    }
}

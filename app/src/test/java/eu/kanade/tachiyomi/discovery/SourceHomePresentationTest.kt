package eu.kanade.tachiyomi.discovery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.discovery.SourceHomePresentation
import tachiyomi.domain.discovery.homeItemKey
import tachiyomi.domain.discovery.homePresentation
import tachiyomi.domain.entries.anime.model.Anime

class SourceHomePresentationTest {
    private val anime = Anime.create().copy(id = 1, source = 42, url = "/series")

    @Test fun cardsDistinguishEpisodesWithoutChangingSeriesIdentity() {
        fun card(id: String) = anime.copy(memo = SourceHomePresentation(id = id).attachTo(anime.memo))
        assertNotEquals(card("9").homeItemKey, card("8").homeItemKey)
        assertEquals(card("9").id, card("8").id)
        assertNotEquals(card("9").homeItemKey, card("9").copy(source = 43).homeItemKey)
        assertEquals(anime.homeItemKey, anime.copy(id = 999).homeItemKey)
    }

    @Test fun missingMalformedAndFutureVersionMetadataUseOrdinaryAnimeCards() {
        assertNull(anime.homePresentation)
        for (input in listOf(
            """{"aniyomi.home.v1":"bad"}""",
            """{"aniyomi.home.v1":{"badges":{}}}""",
            """{"aniyomi.home.v2":{"id":"9"}}""",
        )) {
            val changed = anime.copy(memo = Json.parseToJsonElement(input) as JsonObject)
            assertNull(changed.homePresentation)
            assertEquals(anime.homeItemKey, changed.homeItemKey)
        }
    }

    @Test fun sourceOwnedPresentationIsBoundedAndCannotOverwriteOtherMemoFields() {
        val memo = Json.parseToJsonElement("""{"sourceKey":"kept"}""") as JsonObject
        val bounded = SourceHomePresentation(
            id = "x".repeat(513),
            badges = listOf("", "DUB", "DUB") + List(20) { "Tag $it" },
            details = listOf("Valid", "bad\ncontrol", "x".repeat(301)),
            sectionTitle = "x".repeat(101),
        ).attachTo(memo)
        val result = SourceHomePresentation.from(bounded)!!
        assertNull(result.id)
        assertNull(result.sectionTitle)
        assertEquals(8, result.badges.size)
        assertEquals(listOf("Valid"), result.details)
        assertEquals(memo, SourceHomePresentation.without(bounded))
    }
}

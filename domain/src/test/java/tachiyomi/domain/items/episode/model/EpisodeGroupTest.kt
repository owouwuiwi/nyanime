package tachiyomi.domain.items.episode.model

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.items.episode.service.getEpisodeSort

class EpisodeGroupTest {
    private val empty = JsonObject(emptyMap())
    private val group = EpisodeGroup("opaque:a", "First collection", 0)
    private fun episode(id: Long, number: Double, group: EpisodeGroup? = null) = Episode.create().copy(
        id = id,
        animeId = 10,
        episodeNumber = number,
        memo = group?.attachTo(empty) ?: empty,
    )

    @Test fun `optional group round trips without inspecting titles or addresses`() {
        assertEquals(group, EpisodeGroup.from(group.attachTo(empty)))
        assertNull(EpisodeGroup.from(empty))
    }

    @Test fun `malformed groups remain ordinary episodes`() {
        for (value in listOf(JsonNull, JsonPrimitive("invalid"), JsonObject(emptyMap()))) {
            assertNull(EpisodeGroup.from(JsonObject(mapOf(EpisodeGroup.KEY to value))))
        }
        assertThrows(IllegalArgumentException::class.java) { group.copy(id = "").attachTo(empty) }
        assertThrows(IllegalArgumentException::class.java) { group.copy(order = -1).attachTo(empty) }
        assertThrows(IllegalArgumentException::class.java) { group.copy(title = "x".repeat(201)).attachTo(empty) }
    }

    @Test fun `partial responses preserve grouping but explicit null clears it`() {
        val saved = group.attachTo(empty)
        val partial = JsonObject(mapOf("unrelated" to JsonPrimitive(true)))
        assertEquals(group, EpisodeGroup.from(EpisodeGroup.mergeMemo(saved, partial)))
        assertEquals(JsonPrimitive(true), EpisodeGroup.mergeMemo(saved, partial)["unrelated"])
        val cleared = JsonObject(mapOf(EpisodeGroup.KEY to JsonNull))
        assertNull(EpisodeGroup.from(EpisodeGroup.mergeMemo(saved, cleared)))
    }

    @Test fun `same number in different groups is not duplicate progress`() {
        val a = episode(1, 1.0, group)
        val b = episode(2, 1.0, group.copy(id = "opaque:b", order = 1))
        assertNotEquals(a.progressKey, b.progressKey)
        assertEquals(a.progressKey, a.copy(id = 3).progressKey)
        assertNotEquals(a.progressKey, episode(4, 1.0).progressKey)
    }

    @Test fun `number sort and resume order keep repeated ordinals inside their collection`() {
        val b = group.copy(id = "opaque:b", title = "Second collection", order = 1)
        val entries = listOf(episode(4, 2.0, b), episode(2, 2.0, group), episode(3, 1.0, b), episode(1, 1.0, group))
        val anime = Anime.create().copy(episodeFlags = Anime.EPISODE_SORTING_NUMBER)
        assertEquals(listOf(1L, 2L, 3L, 4L), entries.sortedWith(getEpisodeSort(anime, false)).map { it.id })
        assertEquals(listOf(4L, 3L, 2L, 1L), entries.sortedWith(getEpisodeSort(anime, true)).map { it.id })
        assertEquals(
            listOf(1.0, 2.0),
            listOf(episode(2, 2.0), episode(1, 1.0)).sortedWith(getEpisodeSort(anime, false)).map {
                it.episodeNumber
            },
        )
    }

    @Test fun `mixed metadata still gives a transitive comparator`() {
        val anime = Anime.create().copy(episodeFlags = Anime.EPISODE_SORTING_NUMBER)
        val entries = listOf(episode(1, 8.0, group), episode(2, 1.0), episode(3, 2.0, group))
        assertEquals(listOf(3L, 1L, 2L), entries.sortedWith(getEpisodeSort(anime, false)).map { it.id })
    }
}

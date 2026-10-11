package eu.kanade.presentation.entries.anime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.items.episode.model.Episode
import kotlin.coroutines.coroutineContext

class EpisodeOptionsEntryTest {
    private val anime = Anime.create().copy(id = 42)
    private val episode = Episode.create().copy(id = 7, animeId = anime.id)

    @Test
    fun `existing episode retains the exact playback metadata`() = runBlocking {
        val result =
            loadEpisodeOptionsEntry(
                episode.id,
                anime.id,
                getEpisode = {
                    assertEquals(episode.id, it)
                    episode
                },
                getAnime = {
                    assertEquals(anime.id, it)
                    anime
                },
            )
                .getOrThrow()

        assertSame(episode, result.episode)
        assertSame(anime, result.anime)
    }

    @Test
    fun `an episode removed while the menu opens becomes an ordinary failure`() = runBlocking {
        val result = loadEpisodeOptionsEntry(episode.id, anime.id, { null }, { anime })

        assertTrue(result.exceptionOrNull() is NoSuchElementException)
    }

    @Test
    fun `a removed title becomes an ordinary failure`() = runBlocking {
        val result = loadEpisodeOptionsEntry(episode.id, anime.id, { episode }, { null })

        assertTrue(result.exceptionOrNull() is NoSuchElementException)
    }

    @Test
    fun `an episode cannot be resolved using another titles metadata`() = runBlocking {
        val result =
            loadEpisodeOptionsEntry(episode.id, anime.id, { episode.copy(animeId = 99) }, { anime })

        assertTrue(result.isFailure)
    }

    @Test
    fun `lookup errors reach the menus existing failure state`() = runBlocking {
        val error = IllegalStateException("test database unavailable")
        val result = loadEpisodeOptionsEntry(episode.id, anime.id, { throw error }, { anime })

        assertSame(error, result.exceptionOrNull())
    }

    @Test
    fun `cancellation never becomes a menu error`() {
        val cancelled = CancellationException("menu closed")
        assertSame(
            cancelled,
            assertThrows(CancellationException::class.java) {
                runBlocking {
                    loadEpisodeOptionsEntry(episode.id, anime.id, { throw cancelled }, { anime })
                }
            },
        )
    }

    @Test
    fun `a lookup that swallows cancellation cannot publish stale metadata`() {
        var loadedTitle = false
        assertThrows(CancellationException::class.java) {
            runBlocking {
                loadEpisodeOptionsEntry(
                    episode.id,
                    anime.id,
                    getEpisode = {
                        coroutineContext[Job]!!.cancel()
                        episode
                    },
                    getAnime = {
                        loadedTitle = true
                        anime
                    },
                )
            }
        }
        assertTrue(!loadedTitle)
    }
}

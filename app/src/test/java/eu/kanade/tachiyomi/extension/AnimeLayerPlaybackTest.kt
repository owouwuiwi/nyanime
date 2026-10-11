package eu.kanade.tachiyomi.extension

import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.SerializableHoster.Companion.serialize
import eu.kanade.tachiyomi.animesource.model.SerializableHoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import nyanime.extension.api.layer.AnimeLayerV1
import nyanime.extension.internal.AnimeLayerDispatch
import nyanime.extension.internal.layerHosterVideos
import nyanime.extension.internal.layerReplacementHosters
import nyanime.extension.internal.layerRestoreHosters
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnimeLayerPlaybackTest {
    @Test
    fun `a layer hoster stays with its original layer and cannot fall back to the base`() = runTest {
        var baseCalls = 0
        val source = object : AnimeHttpSource() {
            override val name = "Example"
            override val lang = "en"
            override val baseUrl = "https://example.invalid"
            override val supportsLatest = false

            override suspend fun getVideoList(hoster: Hoster): List<Video> {
                baseCalls++
                return emptyList()
            }
        }
        val episode = SEpisode.create()
        val complete = Hoster(hosterName = "Complete", memo = JsonObject(emptyMap()))
        val missing = Hoster(hosterName = "Missing", memo = JsonObject(emptyMap()))
        val video = Video(videoUrl = "https://example.invalid/episode.m3u8", memo = JsonObject(emptyMap()))
        val first = object : AnimeLayerV1 {
            override suspend fun hosters(episode: SEpisode) = listOf(complete, missing)

            override suspend fun hosterVideos(hoster: Hoster) = listOf(video).takeIf { hoster.hosterName == complete.hosterName }
        }
        val replacement = object : AnimeLayerV1 {
            override suspend fun hosterVideos(hoster: Hoster): List<Video> = error("Wrong layer")
        }
        fun publish(layer: AnimeLayerV1) {
            val binding = AnimeLayerDispatch.Binding(layer) {}
            AnimeLayerDispatch.publish(mapOf(source to mapOf("player" to binding)))
        }

        try {
            publish(first)
            val selected = requireNotNull(source.layerReplacementHosters(episode))
            val handoff = selected.serialize()
            val restored = requireNotNull(source.layerRestoreHosters(handoff.toHosterList()))
            publish(replacement)

            assertEquals(listOf(video), source.layerHosterVideos(selected.first()))
            assertEquals(listOf(video), source.layerHosterVideos(restored.first()))
            assertNull(source.layerRestoreHosters(handoff.toHosterList()))
            val failure = runCatching { source.layerHosterVideos(selected.last()) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals("The source layer did not provide videos for this hoster.", failure?.message)
            assertEquals(0, baseCalls)
        } finally {
            AnimeLayerDispatch.publish(emptyMap())
        }
    }
}

package eu.kanade.presentation.entries.details

import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.entries.manga.MangaScreen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class DetailScreenStateTest {
    @Test
    fun `anime detail navigation can be saved when opening the player or rooms`() {
        val screen = AnimeScreen(123, fromSource = true)
        screen.posterDestinationShape
        val restored = roundTrip(screen) as AnimeScreen
        assertEquals(screen.animeId, restored.animeId)
        assertEquals(screen.fromSource, restored.fromSource)
        assertEquals(screen.key, restored.key)
    }

    @Test
    fun `manga detail navigation can be saved when opening the reader or rooms`() {
        val screen = MangaScreen(456, fromSource = true)
        screen.posterDestinationShape
        val restored = roundTrip(screen) as MangaScreen
        assertEquals(screen.mangaId, restored.mangaId)
        assertEquals(screen.fromSource, restored.fromSource)
        assertEquals(screen.key, restored.key)
    }

    private fun roundTrip(screen: Any): Any {
        val bytes = ByteArrayOutputStream().use { buffer ->
            ObjectOutputStream(buffer).use { it.writeObject(screen) }
            buffer.toByteArray()
        }
        return ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() }
    }
}

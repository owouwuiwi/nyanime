package eu.kanade.tachiyomi.ui.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HeadphoneCinemaTest {
    @Test
    fun `original clears an already registered owned filter without clearing other filters`() {
        val commands = mutableListOf<List<String>>()
        var present = true
        val filter = CinemaFilter(
            command = {
                commands += it.toList()
                present = false
            },
            isPresent = { present },
        )
        assertTrue(filter.apply(null, ""))
        assertEquals(listOf(listOf("af", "remove", "@nyanime_headphone_cinema")), commands)
        assertFalse(present)
    }

    @Test
    fun `native option serialization separates filters without splitting the convolution graph`() {
        val graph = CinemaFilter.graph(CinemaLayout.Surround71, "/data/cinema")
        val owned = "@nyanime_headphone_cinema:lavfi=graph=%${graph.toByteArray().size}%$graph"
        assertEquals(listOf(owned), parseAudioFilters(owned))
        assertEquals(listOf(owned, "@user:volume=0.8"), parseAudioFilters("$owned,@user:volume=0.8"))
        assertEquals(listOf("@custom:test=%5%猫,x", owned), parseAudioFilters("@custom:test=%5%猫,x,$owned"))
        assertEquals(emptyList<String>(), parseAudioFilters(""))
        assertNull(parseAudioFilters("@user:lavfi=graph=%999%short"))
        assertNull(parseAudioFilters("@user:lavfi=graph=%bad%value"))
    }

    @Test
    fun `accept only measured layouts and distinguish side from rear surrounds`() {
        assertEquals(CinemaLayout.Surround51, CinemaLayout.fromNative("5.1"))
        assertEquals(CinemaLayout.Surround51Side, CinemaLayout.fromNative("5.1(side)"))
        assertEquals(CinemaLayout.Surround71, CinemaLayout.fromNative("7.1"))
        listOf(null, "stereo", "mono", "6.1", "7.1(wide)", "unknown").forEach {
            assertNull(CinemaLayout.fromNative(it))
        }
    }

    @Test
    fun `comparison and track changes replace only the owned filter`() {
        val commands = mutableListOf<List<String>>()
        var present = false
        val filter = CinemaFilter(
            command = {
                commands += it.toList()
                present = it[1] == "add"
            },
            isPresent = { present },
        )
        assertTrue(filter.apply(CinemaLayout.Surround51, "/data/user/0/app/files/cinema"))
        repeat(5) { assertTrue(filter.apply(CinemaLayout.Surround51, "/data/user/0/app/files/cinema")) }
        assertEquals(1, commands.size, "Route polling must not rebuild an active pipeline")
        assertTrue(filter.apply(CinemaLayout.Surround71, "/data/user/0/app/files/cinema"))
        assertEquals(listOf("af", "remove", "@nyanime_headphone_cinema"), commands[1])
        assertTrue(commands[2][2].contains("surround71.wav"))
        filter.apply(null, "")
        filter.apply(null, "")
        assertEquals(4, commands.size)
        assertTrue(commands.all { it[0] == "af" && it[1] in setOf("add", "remove") })
    }

    @Test
    fun `missing native filter never reports success and can retry`() {
        val commands = mutableListOf<List<String>>()
        var present = false
        val filter = CinemaFilter({ commands += it.toList() }, { present })
        assertFalse(filter.apply(CinemaLayout.Surround51, "/data/cinema"))
        assertEquals(listOf("af", "remove", "@nyanime_headphone_cinema"), commands.last())
        present = true
        assertTrue(filter.apply(CinemaLayout.Surround51, "/data/cinema"))
        present = false
        assertFalse(filter.apply(CinemaLayout.Surround51, "/data/cinema"))
    }

    @Test
    fun `no native calls after close even for late callbacks`() {
        var closed = false
        val filter = CinemaFilter({ check(!closed) }, {
            check(!closed)
            false
        })
        filter.close()
        closed = true
        assertFalse(filter.apply(CinemaLayout.Surround71, "/data/cinema"))
        filter.remove()
        filter.close()
    }

    @Test
    fun `graph preserves timestamps and reserves headroom without changing playback speed`() {
        CinemaLayout.entries.forEach { layout ->
            val graph = CinemaFilter.graph(layout, "/data/cinema")
            assertTrue(graph.contains("size=1024"))
            assertTrue(graph.contains("latency=true"))
            assertTrue(graph.contains("level=false"))
            assertTrue(graph.contains("aformat=channel_layouts='${layout.nativeName}'"))
            assertFalse(graph.contains("asetpts"))
            assertFalse(graph.contains("atempo"))
            assertEquals(layout.count, layout.channels.split('|').size)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CinemaFilter.graph(CinemaLayout.Surround71, "/tmp/unsafe'[in];")
        }
    }
}

package eu.kanade.tachiyomi.data.download.anime.ultra

import eu.kanade.tachiyomi.ui.player.Anime4KMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class UltraShaderGraphTest {
    private fun shaders() = UltraShaderGraph.assets.map { File("src/main/assets/anime4k/$it").readText() }

    @Test
    fun `uses the full maximum preset without replacing CNN with ordinary scaling`() {
        assertEquals(Anime4KMode.ModeAPlusHq.shaderFileNames, UltraShaderGraph.assets)
        val graph = UltraShaderGraph.compile(shaders(), 1280, 720)
        assertTrue(graph.nodes.size > 30)
        assertTrue(graph.nodes.any { "mat4(" in it.fragment })
        assertEquals(3840 to 2160, graph.output.width to graph.output.height)
    }

    @Test
    fun `all standard sizes have valid ordered dependencies and finite dimensions`() {
        for ((w, h) in listOf(320 to 180, 640 to 480, 1280 to 720, 1920 to 1080, 1080 to 1920, 3840 to 2160)) {
            val graph = UltraShaderGraph.compile(shaders(), w, h)
            val images = mutableSetOf(0)
            for (node in graph.nodes) {
                assertTrue(node.inputs.isNotEmpty())
                assertTrue(node.inputs.values.all { it.id in images })
                assertTrue(node.output.width > 0 && node.output.height > 0)
                images += node.output.id
            }
            assertTrue(maxOf(graph.output.width, graph.output.height) <= 3840)
            assertTrue(kotlin.math.abs(graph.output.width.toDouble() / graph.output.height - w.toDouble() / h) < 0.005)
        }
    }

    @Test
    fun `clamping runs after restoration and upscaling despite asset load order`() {
        val nodes = UltraShaderGraph.compile(shaders(), 1280, 720).nodes
        assertTrue("new_luma" in nodes[nodes.lastIndex - 1].fragment)
        assertTrue(nodes[nodes.lastIndex - 1].inputs.size == 2)
    }

    @Test
    fun `target preserves portrait and four by three geometry with even codec dimensions`() {
        assertEquals(2880 to 2160, UltraShaderGraph.target(1440, 1080))
        assertEquals(2160 to 3840, UltraShaderGraph.target(1080, 1920))
        assertEquals(1280 to 720, UltraShaderGraph.target(320, 180))
        assertThrows(IllegalArgumentException::class.java) { UltraShaderGraph.target(0, 720) }
        assertThrows(IllegalArgumentException::class.java) { UltraShaderGraph.target(7680, 4320) }
    }

    @Test
    fun `rejects malformed or nonfinite shader expressions`() {
        assertEquals(
            1.0,
            UltraShaderGraph.expression("OUTPUT.w MAIN.w / 1.2 >") {
                if (it ==
                    "OUTPUT.w"
                ) {
                    3840.0
                } else {
                    1280.0
                }
            },
        )
        assertEquals(0.0, UltraShaderGraph.expression("1 2 >") { error(it) })
        assertThrows(IllegalArgumentException::class.java) { UltraShaderGraph.expression("1 0 /") { error(it) } }
        assertThrows(IllegalArgumentException::class.java) { UltraShaderGraph.expression("+") { error(it) } }
    }
}

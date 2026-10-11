package eu.kanade.tachiyomi.ui.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

class Anime4KShaderPipelineTest {
    @TempDir
    lateinit var directory: File
    private var shaders: String? = ""
    private val commands = mutableListOf<List<String>>()
    private fun pipeline() = Anime4KShaderPipeline(
        shaderDir = File(directory, "owned"),
        assetReader = { ("shader contents " + it).byteInputStream() },
        shaderListReader = { shaders },
        commandSender = {
            commands += it.toList()
            shaders = it[3]
        },
    )

    @Test
    fun `asset verification repairs empty and same-size corrupted shaders`() {
        val pipeline = pipeline()
        assertTrue(pipeline.copyAssets())
        val files = Anime4K.bundledShaderNames.map { File(directory, "owned/$it") }
        val original = files[0].readBytes()
        files[0].writeBytes(ByteArray(original.size))
        files[1].writeText("")
        assertTrue(pipeline.copyAssets())
        assertTrue(original.contentEquals(files[0].readBytes()))
        assertTrue(files[1].length() > 0)
        assertFalse(File(directory, "owned").listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test
    fun `all presets install and repeated application does not recompile shaders`() {
        val pipeline = pipeline()
        assertTrue(pipeline.copyAssets())
        for (mode in Anime4KMode.entries) {
            assertTrue(pipeline.apply(mode))
            val count = commands.size
            assertTrue(pipeline.apply(mode))
            assertEquals(count, commands.size)
        }
    }

    @Test
    fun `same-name custom shaders and their ordering survive mode changes and off`() {
        val pipeline = pipeline()
        assertTrue(pipeline.copyAssets())
        val custom = File(directory, "user/Anime4K_Clamp_Highlights.glsl").absolutePath
        val other = File(directory, "user/other.glsl").absolutePath
        val userList = listOf(custom, other, custom)
        shaders = userList.joinToString(File.pathSeparator)
        assertTrue(pipeline.apply(Anime4KMode.ModeA))
        assertEquals(userList, shaders!!.split(File.pathSeparator).take(3))
        assertTrue(pipeline.apply(Anime4KMode.Off))
        assertEquals(userList.joinToString(File.pathSeparator), shaders)
    }

    @Test
    fun `unreadable MPV list never clears user shaders`() {
        val pipeline = pipeline()
        assertTrue(pipeline.copyAssets())
        shaders = null
        assertFalse(pipeline.apply(Anime4KMode.ModeA))
        assertFalse(pipeline.apply(Anime4KMode.Off))
        assertTrue(commands.isEmpty())
    }

    @Test
    fun `missing assets never send a partial preset`() {
        val pipeline = pipeline()
        assertTrue(pipeline.copyAssets())
        File(directory, "owned/Anime4K_Restore_CNN_M.glsl").delete()
        assertFalse(pipeline.apply(Anime4KMode.ModeA))
        assertTrue(commands.isEmpty())
        assertTrue(pipeline.apply(Anime4KMode.Off))
    }

    @Test
    fun `copy failure and rejected commands are reported`() {
        val broken = Anime4KShaderPipeline(File(directory, "broken"), { throw IOException("unavailable") }, { "" }, {})
        assertFalse(broken.copyAssets())
        val rejected = Anime4KShaderPipeline(
            File(directory, "owned"),
            { it.byteInputStream() },
            { "" },
            { throw IllegalStateException("player shutting down") },
        )
        assertTrue(rejected.copyAssets())
        assertFalse(rejected.apply(Anime4KMode.ModeA))
    }

    @Test
    fun `silently rejected native commands cannot report the requested preset as active`() {
        var actual = ""
        val pipeline = Anime4KShaderPipeline(
            File(directory, "owned"),
            { it.byteInputStream() },
            { actual },
            { commands += it.toList() },
        )
        assertTrue(pipeline.copyAssets())
        assertFalse(pipeline.apply(Anime4KMode.ModeA))
        actual = Anime4KMode.ModeA.shaderFileNames.joinToString(File.pathSeparator) {
            File(directory, "owned/$it").absolutePath
        }
        assertFalse(pipeline.apply(Anime4KMode.Off))
        assertTrue(pipeline.apply(Anime4KMode.ModeA))
        assertEquals(2, commands.size)
    }

    @Test
    fun `failed revalidation cannot activate partially verified cached assets`() {
        var failRead = false
        val pipeline = Anime4KShaderPipeline(
            File(directory, "owned"),
            { if (failRead) throw IOException("storage unavailable") else it.byteInputStream() },
            { "" },
            { commands += it.toList() },
        )
        assertTrue(pipeline.copyAssets())
        failRead = true
        assertFalse(pipeline.copyAssets())
        assertFalse(pipeline.apply(Anime4KMode.ModeAHq))
        assertTrue(commands.isEmpty())
        assertTrue(pipeline.apply(Anime4KMode.Off))
    }
}

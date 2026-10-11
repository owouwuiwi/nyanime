package nyanime.secretgame

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.util.Base64

class SceneStoreTest {
    @TempDir
    lateinit var directory: File

    private fun scene(material: Int = 2, cells: ByteArray = ByteArray(360_000)): String = JsonObject(
        mapOf(
            "v" to JsonPrimitive(1),
            "cells" to JsonPrimitive(Base64.getEncoder().encodeToString(cells)),
            "material" to JsonPrimitive(material),
            "brush" to JsonPrimitive(2),
        ),
    ).toString()

    @Test
    fun `new game has no persisted scene and a committed scene round trips`() {
        val store = SceneStore(File(directory, "world.scene"))
        assertNull(store.read())
        val creation = scene()
        store.write(creation)
        assertEquals(creation, store.read())
        store.write(scene(18))
        assertEquals(scene(18), store.read())
    }

    @Test
    fun `invalid enum values cannot reach WASM or replace a valid creation`() {
        val store = SceneStore(File(directory, "world.scene"))
        val creation = scene()
        store.write(creation)
        val bad = ByteArray(360_000).apply { this[0] = 10 }
        assertThrows(IllegalArgumentException::class.java) { store.write(scene(cells = bad)) }
        assertEquals(creation, store.read())
    }

    @Test
    fun `interrupted temporary save is ignored and failed write preserves committed file`() {
        val file = File(directory, "world.scene")
        val store = SceneStore(file)
        store.write(scene())
        File(directory, "world.scene.pending").writeText("interrupted")
        assertEquals(scene(), store.read())
        File(directory, "world.scene.pending").delete()
        Files.createDirectory(File(directory, "world.scene.pending").toPath())
        assertThrows(Exception::class.java) { store.write(scene(3)) }
        assertEquals(scene(), store.read())
    }

    @Test
    fun `corrupted checksum and oversized files are detected without rewriting them`() {
        val file = File(directory, "world.scene")
        val store = SceneStore(file)
        store.write(scene())
        val bad = file.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        file.writeBytes(bad)
        assertThrows(IllegalArgumentException::class.java) { store.read() }
        assertTrue(bad.contentEquals(file.readBytes()))
        file.writeBytes(ByteArray(SandspielScene.MAX_BYTES + 45))
        assertThrows(IllegalArgumentException::class.java) { store.read() }
    }

    @Test
    fun `snapshot messages must arrive in order and within the bounded transfer`() {
        val assembler = SnapshotAssembler()
        assembler.begin(1, 2)
        assembler.append(1, 0, "first")
        assertThrows(IllegalArgumentException::class.java) { assembler.finish(1) }
        assembler.append(1, 1, "second")
        assertEquals("firstsecond", assembler.finish(1))
        assertThrows(IllegalArgumentException::class.java) { assembler.append(1, 2, "late") }
        assertThrows(IllegalArgumentException::class.java) { assembler.begin(3, 25) }
        assembler.begin(4, 1)
        assertThrows(IllegalArgumentException::class.java) { assembler.append(4, 0, "x".repeat(24_001)) }
    }
}

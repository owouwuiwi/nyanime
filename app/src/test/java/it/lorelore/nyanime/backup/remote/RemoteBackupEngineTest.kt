package it.lorelore.nyanime.backup.remote

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID

class RemoteBackupEngineTest {
    @TempDir lateinit var directory: File
    private val key = ByteArray(32) { it.toByte() }
    private val device = UUID.randomUUID().toString()

    @Test fun `complete encrypted copy can be restored on another installation`() = runBlocking<Unit> {
        val store = MemoryStore()
        val engine = RemoteBackupEngine(store, directory)
        val clear = File(directory, "original").apply { writeBytes(ByteArray(1250000) { (it % 251).toByte() }) }
        val phases = mutableListOf<String>()
        val entry = engine.upload(
            clear,
            File(directory, "encrypted"),
            UUID.randomUUID().toString(),
            device,
            "Test device",
            1234L,
            key,
        ) { phases += it }
        assertEquals(listOf("encrypting", "uploading", "verifying"), phases)
        assertEquals(listOf(entry), engine.list(key))
        val restored = File(directory, "restored")
        RemoteBackupEngine(store, directory).restore(entry, restored, key.copyOf()) { assertTrue(it.length() > 0) }
        assertArrayEquals(clear.readBytes(), restored.readBytes())
        assertFalse(store.values.values.any { it.contentEquals(clear.readBytes()) })
    }

    @Test fun `changed payload is never committed or offered for restore`() = runBlocking<Unit> {
        val store = MemoryStore(corrupt = true)
        val engine = RemoteBackupEngine(store, directory)
        val file = File(directory, "clear").apply { writeText("Test backup contents") }
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                engine.upload(
                    file,
                    File(directory, "encrypted"),
                    UUID.randomUUID().toString(),
                    device,
                    "Device",
                    1234L,
                    key,
                ) {}
            }
        }
        assertTrue(engine.list(key).isEmpty())
        assertFalse(store.values.keys.any { it.endsWith(".json") })
    }

    @Test fun `wrong key and invalid backup do not leave a restored file`() = runBlocking<Unit> {
        val store = MemoryStore()
        val engine = RemoteBackupEngine(store, directory)
        val clear = File(directory, "clear").apply { writeText("Example contents") }
        val entry = engine.upload(
            clear,
            File(directory, "encrypted"),
            UUID.randomUUID().toString(),
            device,
            "Device",
            1234L,
            key,
        ) {}
        assertTrue(engine.list(ByteArray(32) { 7 }).isEmpty())
        val output = File(directory, "restored")
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                engine.restore(entry, output, key) { error("Invalid backup") }
            }
        }
        assertFalse(output.exists())
        output.writeText("Earlier private data")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                engine.restore(entry, output, ByteArray(32) { 7 }) {}
            }
        }
        assertFalse(output.exists())
        val tampered = entry.copy(commit = entry.commit.copy(created = 1235))
        assertFalse(tampered.commit.verify(key))
    }

    @Test fun `retention preserves other devices and unverified objects`() = runBlocking<Unit> {
        val store = MemoryStore()
        val engine = RemoteBackupEngine(store, directory)
        val clear = File(directory, "clear").apply { writeText("Example") }
        val foreign = UUID.randomUUID().toString()
        val entries = (1..5).map { number ->
            engine.upload(
                clear,
                File(directory, "encrypted-$number"),
                UUID.randomUUID().toString(),
                if (number == 1) foreign else device,
                "Device",
                number.toLong(),
                key,
            ) {}
        }
        val orphan = "nyanime-${UUID.randomUUID()}.nyabk.enc"
        store.values[orphan] = byteArrayOf(1)
        engine.retain(key, device, entries.last().commit.id, 3)
        assertEquals(4, engine.list(key).size)
        assertTrue(store.values.containsKey(entries.first().commit.payload))
        assertTrue(store.values.containsKey(orphan))
        assertThrows(IllegalStateException::class.java) {
            runBlocking { engine.retain(key, device, UUID.randomUUID().toString(), 3) }
        }
    }

    @Test fun `retry reuses the same encrypted object`() = runBlocking<Unit> {
        val store = MemoryStore()
        val engine = RemoteBackupEngine(store, directory)
        val clear = File(directory, "clear").apply { writeText("Original") }
        val encrypted = File(directory, "encrypted")
        val id = UUID.randomUUID().toString()
        val first = engine.upload(clear, encrypted, id, device, "Device", 1, key) {}
        clear.writeText("Later content")
        val second = engine.upload(clear, encrypted, id, device, "Device", 1, key) {}
        assertEquals(first, second)
        assertEquals(2, store.values.size)
    }

    @Test fun `recovery key survives copy and rejects truncation`() {
        val encoded = RemoteRecoveryKey.create()
        assertEquals(encoded, RemoteRecoveryKey.encode(RemoteRecoveryKey.decode(encoded)))
        assertThrows(IllegalArgumentException::class.java) { RemoteRecoveryKey.decode(encoded.dropLast(1)) }
    }

    @Test fun `remote JSON nesting is bounded before decoding`() {
        boundedRemoteJson("[".repeat(12) + "0" + "]".repeat(12))
        assertThrows(IllegalArgumentException::class.java) {
            boundedRemoteJson("[".repeat(13) + "0" + "]".repeat(13))
        }
    }

    @Test fun `interrupted commit leaves the previous verified backup available`() = runBlocking<Unit> {
        val store = MemoryStore()
        val engine = RemoteBackupEngine(store, directory)
        val clear = File(directory, "clear").apply { writeText("Example") }
        val previous = engine.upload(
            clear,
            File(directory, "first"),
            UUID.randomUUID().toString(),
            device,
            "Device",
            1,
            key,
        ) {}
        store.stopBeforeCommit = true
        assertThrows(RemoteStorageException::class.java) {
            runBlocking {
                engine.upload(
                    clear,
                    File(directory, "second"),
                    UUID.randomUUID().toString(),
                    device,
                    "Device",
                    2,
                    key,
                ) {}
            }
        }
        assertEquals(listOf(previous), engine.list(key))
        assertTrue(store.values.containsKey(previous.commit.payload))
    }

    private class MemoryStore(private val corrupt: Boolean = false) : RemoteFileStore {
        val values = linkedMapOf<String, ByteArray>()
        var stopBeforeCommit = false
        override suspend fun prepare() = Unit
        override suspend fun list() = values.map { RemoteObject(it.key, it.key, it.value.size.toLong()) }
        override suspend fun upload(name: String, file: File): RemoteObject {
            if (stopBeforeCommit && name.endsWith(".json")) throw RemoteStorageException("network")
            values.putIfAbsent(
                name,
                file.readBytes().also {
                    if (corrupt) {
                        it[it.lastIndex] =
                            (it.last().toInt() xor 1).toByte()
                    }
                },
            )
            return RemoteObject(name, name, file.length())
        }
        override suspend fun download(id: String, file: File, limit: Long) {
            val bytes = requireNotNull(values[id])
            require(bytes.size <= limit)
            file.writeBytes(bytes)
        }
        override suspend fun delete(id: String) {
            values.remove(id)
        }
    }
}

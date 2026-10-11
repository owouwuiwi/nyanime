package it.lorelore.nyanime.cloud.sync

import nyanime.telegram.sync.SyncPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID

class SyncCheckpointPackTest {
    @TempDir lateinit var directory: File
    private fun id() = UUID.randomUUID().toString()
    private val space = id()
    private val parent = SyncPacket(kind = "checkpoint-pack", id = id(), space = space, payload = "encrypted index")
    private fun child() = SyncPacket(kind = "checkpoint", id = id(), space = space, payload = "encrypted chunk " + id())
    private fun index(children: List<SyncPacket>): SyncCheckpointPackIndex {
        var offset = 0L
        return SyncCheckpointPackIndex(
            checkpoint = id(),
            chunks = children.map {
                SyncCheckpointPack.entry(it, offset).also { entry -> offset += 4L + entry.bytes }
            },
        )
    }
    private fun pack(children: List<SyncPacket>, name: String = id()): File = File(directory, name).also { file ->
        file.outputStream().use { SyncCheckpointPack.write(it, parent, children.asSequence()) }
    }

    @Test fun oneDocumentRetains128IndividuallyBoundedRandomAccessBlocks() {
        val children = List(128) { child() }
        val index = index(children)
        val file = pack(children)
        assertEquals(parent, SyncCheckpointPack.inspect(file))
        for (position in children.indices.reversed()) {
            assertEquals(children[position], SyncCheckpointPack.packet(file, parent, index, position))
        }
        assertEquals(128, index.chunks.size)
    }

    @Test fun hashesIdentityOrderSpaceAndParentCannotBeSubstituted() {
        val original = List(3) { child() }
        val index = index(original)
        val changed = original.toMutableList().also { it[1] = it[1].copy(payload = "altered encrypted content") }
        val swapped = original.reversed()
        for (children in listOf(changed, swapped)) {
            val file = pack(children)
            val changedPosition = children.indices.first { children[it] != original[it] }
            assertThrows(Exception::class.java) { SyncCheckpointPack.packet(file, parent, index, changedPosition) }
        }
        val file = pack(original)
        assertThrows(Exception::class.java) { SyncCheckpointPack.packet(file, parent.copy(id = id()), index, 0) }
        assertThrows(Exception::class.java) { pack(listOf(child().copy(space = id()))) }
        assertThrows(Exception::class.java) { pack(listOf(child().copy(kind = "data"))) }
    }

    @Test fun malformedOffsetsSizesVersionsAndDuplicateIdentitiesAreRejected() {
        val children = List(2) { child() }
        val valid = index(children)
        val invalid = listOf(
            valid.copy(version = 2), valid.copy(checkpoint = "not-an-id"), valid.copy(chunks = emptyList()),
            valid.copy(chunks = List(129) { valid.chunks.first() }),
            valid.copy(chunks = valid.chunks.map { it.copy(id = valid.chunks.first().id) }),
            valid.copy(chunks = listOf(valid.chunks.first().copy(bytes = Int.MAX_VALUE))),
            valid.copy(chunks = valid.chunks.map { it.copy(offset = -1) }),
            valid.copy(chunks = valid.chunks.map { it.copy(hash = "bad") }),
            valid.copy(chunks = listOf(valid.chunks[1], valid.chunks[0])),
        )
        invalid.forEach { value -> assertThrows(Exception::class.java) { value.validate() } }
        assertThrows(Exception::class.java) { pack(emptyList()) }
        assertThrows(Exception::class.java) { pack(listOf(children[0], children[0])) }
        assertThrows(Exception::class.java) { pack(List(129) { child() }) }
    }

    @Test fun incompleteFramesTrailingDataAndHostileLengthsFailBeforeUnboundedAllocation() {
        val children = List(2) { child() }
        val valid = pack(children).readBytes()
        for (length in listOf(0, 3, 19, 22, valid.size / 2, valid.size - 1)) {
            val file = File(directory, id()).apply { writeBytes(valid.copyOf(length)) }
            assertThrows(Exception::class.java) { SyncCheckpointPack.inspect(file) }
        }
        val trailing = File(directory, id()).apply { writeBytes(valid + byteArrayOf(0, 0, 0, 1, 65)) }
        assertThrows(Exception::class.java) { SyncCheckpointPack.inspect(trailing) }
        assertThrows(Exception::class.java) { SyncCheckpointPack.packet(trailing, parent, index(children), 0) }
        for (size in listOf(-1, Int.MAX_VALUE, SyncCheckpointPack.MAX_HEADER_BYTES + 1)) {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use {
                it.write("NYANIME_SYNC_PACK_1\n".toByteArray())
                it.writeInt(size)
            }
            val file = File(directory, id()).apply { writeBytes(bytes.toByteArray()) }
            assertThrows(Exception::class.java) { SyncCheckpointPack.inspect(file) }
        }
    }
}

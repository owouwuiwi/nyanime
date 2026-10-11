package nyanime.telegram.sync

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class SyncCheckpointTest {
    @Test fun checkpointAuthenticatesMembershipPartsAndTheirContent() {
        val author = SyncDeviceCrypto.generate(UUID.randomUUID().toString())
        val space = UUID.randomUUID().toString()
        val checkpoint = UUID.randomUUID().toString()
        val members = SyncMembershipState(
            space,
            1,
            UUID.randomUUID().toString(),
            1,
            mapOf(author.public.id to author.public),
        )
        val change = SyncChange(
            SyncDot(author.public.id, 1),
            SyncVector(),
            SyncRef("item", 10, "/book", "/chapter"),
            "bookmark",
            JsonPrimitive(true),
            10,
        )
        val chunk = SyncCheckpointChunk(checkpoint = checkpoint, changes = listOf(change))
        val key = SyncCipher.newKey()
        val clear = syncJson.encodeToString(chunk).toByteArray()
        val envelope = SyncCipher.encrypt(10, space, 1, UUID.randomUUID().toString(), key, clear)
        val part = SyncCheckpointPart(envelope.id, SyncCheckpoints.hash(syncJson.encodeToString(envelope)), 2)
        val commit = SyncCheckpointCommit(
            id = checkpoint,
            space = space,
            epoch = 1,
            author = author.public.id,
            throughMessage = 1,
            parts = listOf(part),
        )
        val signed = SyncCheckpoints.sign(commit, 10, author)
        assertEquals(commit, SyncCheckpoints.verify(signed, 10, members))
        assertEquals(chunk, SyncCheckpoints.verifyPart(part, envelope, checkpoint, clear))
        assertThrows(Exception::class.java) { SyncCheckpoints.verify(signed, 11, members) }
        assertThrows(Exception::class.java) { SyncCheckpoints.verify(signed, 10, members.copy(devices = emptyMap())) }
        assertThrows(Exception::class.java) {
            SyncCheckpoints.verifyPart(part.copy(hash = "0".repeat(64)), envelope, checkpoint, clear)
        }
        assertThrows(Exception::class.java) {
            SyncCheckpoints.verifyPart(part, envelope, UUID.randomUUID().toString(), clear)
        }
        assertThrows(Exception::class.java) {
            SyncCheckpoints.sign(commit.copy(parts = listOf(part, part)), 10, author)
        }
    }

    @Test fun pendingArchiveDescriptorIsDistinctFromACompletedArchive() {
        val space = UUID.randomUUID().toString()
        val pending = SyncArchiveDescriptor(space, 0)
        assertEquals(pending, SyncArchiveDescriptor.parse(pending.encode()))
        assertNull(SyncArchiveDescriptor.parse(pending.encode() + "\nextra"))
        assertNull(SyncArchiveDescriptor.parse("${SyncEnvelope.ARCHIVE_MARKER}\n$space\n-1"))
    }
}

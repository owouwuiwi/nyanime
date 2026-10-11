package eu.kanade.presentation.entries.details

import eu.kanade.tachiyomi.data.backup.models.BackupEpisode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.domain.items.episode.model.EpisodeGroup
import tachiyomi.domain.items.episode.model.group

class EpisodeGroupBackupTest {
    @Test fun `existing backup format retains grouping and exact progress`() {
        val group = EpisodeGroup("opaque:1", "First collection", 0)
        val backup = BackupEpisode(
            url = "/opaque/item",
            name = "Entry",
            episodeNumber = 1f,
            lastSecondSeen = 123_456,
            totalSeconds = 1_400_000,
            seen = true,
            bookmark = true,
            memo = MemoColumnAdapter.encode(group.attachTo(JsonObject(emptyMap()))),
        )
        val restored = ProtoBuf.decodeFromByteArray(
            BackupEpisode.serializer(),
            ProtoBuf.encodeToByteArray(BackupEpisode.serializer(), backup),
        ).toEpisodeImpl()
        assertEquals(group, restored.group)
        assertEquals(123_456L, restored.lastSecondSeen)
        assertEquals(true, restored.seen)
        assertEquals(true, restored.bookmark)
        assertEquals("/opaque/item", restored.url)
    }

    @Test fun `older backups without grouping remain compatible`() {
        val restored = BackupEpisode("/opaque/item", "Entry").toEpisodeImpl()
        assertNull(restored.group)
    }
}

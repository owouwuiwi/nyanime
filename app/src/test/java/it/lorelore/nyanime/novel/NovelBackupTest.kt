package it.lorelore.nyanime.novel

import eu.kanade.tachiyomi.data.backup.BackupDetector
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.Backup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovelBackupTest {
    private val id = NovelIdentity.local("a".repeat(64))
    private val book =
        NovelBook(
            id,
            "Own novel",
            format = NovelFormat.EPUB,
            contentVersion = "a".repeat(64),
            importedAt = 0,
            modifiedAt = 1,
        )
    private val point = NovelPosition(id, "a".repeat(64), "{\"href\":\"chapter.xhtml\"}", 2, .5)
    private val note =
        NovelAnnotation(
            bookId = id,
            contentVersion = "a".repeat(64),
            locatorJson = point.locatorJson,
            kind = NovelAnnotationKind.NOTE,
            note = "Own note",
            createdAt = 2,
            modifiedAt = 3,
        )
    private val records =
        listOf(
            NovelRecord("book", id, id, 1, Json.encodeToJsonElement(book)),
            NovelRecord("position", id, id, 2, Json.encodeToJsonElement(point)),
            NovelRecord("annotation", note.id, id, 3, Json.encodeToJsonElement(note)),
            NovelRecord(
                "appearance",
                "global",
                null,
                4,
                Json.encodeToJsonElement(NovelAppearance()),
            ),
        )

    @Test
    fun `novel-only backup round trips while older payloads omit optional metadata`() {
        val archive = NovelArchive(records = records)
        val original = Backup(isLegacy = false, novelMetadata = archive.encode())
        val bytes = ProtoBuf.encodeToByteArray(Backup.serializer(), original)
        assertFalse(BackupDetector.isLegacyBackup(bytes))
        val restored = ProtoBuf.decodeFromByteArray(Backup.serializer(), bytes)
        assertEquals(archive, NovelBackup.validate(requireNotNull(restored.novelMetadata)))
        assertTrue(restored.backupAnime.isEmpty() && restored.backupManga.isEmpty())
        val previous = ProtoBuf.encodeToByteArray(Backup.serializer(), Backup(isLegacy = false))
        assertNull(ProtoBuf.decodeFromByteArray(Backup.serializer(), previous).novelMetadata)
    }

    @Test
    fun `partial backup selects positions settings and non-favorite books explicitly`() {
        val archive = NovelArchive(records = records)
        assertEquals(
            setOf("book", "annotation"),
            NovelBackup.select(archive, BackupOptions(chapters = false, appSettings = false))
                .map { it.kind }
                .toSet(),
        )
        assertEquals(
            listOf(records.last()),
            NovelBackup.select(archive, BackupOptions(libraryEntries = false)),
        )
        val hidden = book.copy(favorite = false)
        val nonFavorite =
            NovelArchive(
                records =
                records.map {
                    if (it.kind == "book") {
                        it.copy(payload = Json.encodeToJsonElement(hidden))
                    } else {
                        it
                    }
                },
            )
        assertEquals(
            listOf(records.last()),
            NovelBackup.select(nonFavorite, BackupOptions(readEntries = false)),
        )
    }
}

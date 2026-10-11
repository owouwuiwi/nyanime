package it.lorelore.nyanime.tracking

import eu.kanade.tachiyomi.data.backup.models.Backup
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrackingRecordTest {
    private val subject = TrackingSubject(TrackingMedium.MANGA, 71, "/work/fictional-volume")

    @Test fun `portable identity separates media and exact source references`() {
        assertEquals(subject.key, subject.copy().key)
        assertNotEquals(subject.key, subject.copy(medium = TrackingMedium.ANIME).key)
        assertNotEquals(subject.key, subject.copy(source = 72).key)
        assertNotEquals(subject.key, subject.copy(url = "/work/other").key)
        assertEquals(64, subject.key.length)
    }

    @Test fun `changing score preserves fractional chapters and every unrelated field`() {
        val before = TrackingValues(
            status = 1,
            progress = 12.5,
            score = 65.0,
            startDate = 500,
            finishDate = 700,
            private = true,
            notes = "A personal note",
            tags = listOf("weekend"),
        )
        val after = TrackingPatch(TrackingValues(score = 80.0), setOf(TrackingField.SCORE)).applyTo(before)
        assertEquals(before.copy(score = 80.0), after)
    }

    @Test fun `manual progress may decrease and reach zero`() {
        val before = TrackingValues(progress = 43.75)
        assertEquals(
            2.5,
            TrackingPatch(TrackingValues(progress = 2.5), setOf(TrackingField.PROGRESS)).applyTo(before).progress,
            0.0,
        )
        assertEquals(
            0.0,
            TrackingPatch(TrackingValues(progress = 0.0), setOf(TrackingField.PROGRESS)).applyTo(before).progress,
            0.0,
        )
    }

    @Test fun `coalescing preserves the newest value of each changed field`() {
        val score = TrackingPatch(TrackingValues(score = 8.0), setOf(TrackingField.SCORE))
        val progress = TrackingPatch(TrackingValues(progress = 6.5), setOf(TrackingField.PROGRESS))
        val newerScore = TrackingPatch(TrackingValues(score = 9.0), setOf(TrackingField.SCORE))
        val combined = score.then(progress).then(newerScore)
        assertEquals(TrackingValues(score = 9.0, progress = 6.5), combined.applyTo(TrackingValues()))
        assertEquals(setOf(TrackingField.SCORE, TrackingField.PROGRESS), combined.fields)
    }

    @Test fun `undo restores the edit without reverting unrelated new data`() {
        val before = TrackingValues(progress = 5.5, score = 8.0)
        val patch = TrackingPatch(before.copy(score = 9.0), setOf(TrackingField.SCORE))
        val current = patch.applyTo(before).copy(progress = 7.5, notes = "A newer note")
        assertEquals(current.copy(score = 8.0), patch.inverse(before, current).applyTo(current))
    }

    @Test fun `undo does not overwrite a newer edit to the same field`() {
        val before = TrackingValues(score = 7.0)
        val patch = TrackingPatch(before.copy(score = 8.0), setOf(TrackingField.SCORE))
        val current = before.copy(score = 9.0)
        assertTrue(patch.inverse(before, current).fields.isEmpty())
        assertEquals(current, patch.inverse(before, current).applyTo(current))
    }

    @Test fun `undo changes only fields still owned by its edit`() {
        val before = TrackingValues(progress = 3.0, score = 6.0)
        val patch =
            TrackingPatch(before.copy(progress = 5.0, score = 8.0), setOf(TrackingField.PROGRESS, TrackingField.SCORE))
        val current = patch.applyTo(before).copy(progress = 7.0)
        val inverse = patch.inverse(before, current)
        assertEquals(setOf(TrackingField.SCORE), inverse.fields)
        assertEquals(current.copy(score = 6.0), inverse.applyTo(current))
    }

    @Test fun `old observed progress cannot undo a deliberate manual rewind`() {
        val record = PersonalTrackingRecord(subject, TrackingValues(progress = 3.5), observedProgress = 10.0)
        assertEquals(record, record.observe(10.0))
        assertEquals(record, record.observe(7.0))
        assertEquals(11.0, record.observe(11.0).values.progress, 0.0)
    }

    @Test fun `first observed progress starts an unstarted local record`() {
        val record = PersonalTrackingRecord(subject).observe(2.5)
        assertEquals(2.5, record.values.progress, 0.0)
        assertEquals(PersonalTrackingStatus.CURRENT.ordinal.toLong(), record.values.status)
        assertEquals(2.5, record.observedProgress, 0.0)
    }

    @Test fun `observation preserves explicit paused dropped and repeating choices`() {
        for (status in listOf(
            PersonalTrackingStatus.PAUSED,
            PersonalTrackingStatus.DROPPED,
            PersonalTrackingStatus.REPEATING,
        )) {
            val record = PersonalTrackingRecord(subject, TrackingValues(status = status.ordinal.toLong()))
            assertEquals(status.ordinal.toLong(), record.observe(3.0).values.status)
        }
    }

    @Test fun `invalid observations cannot poison personal progress`() {
        val record = PersonalTrackingRecord(subject)
        for (invalid in listOf(
            Double.NaN,
            Double.POSITIVE_INFINITY,
            -1.0,
            1_000_001.0,
        )) {
            assertEquals(record, record.observe(invalid))
        }
    }

    @Test fun `untrusted local records reject impossible values and oversized notes`() {
        val record = PersonalTrackingRecord(subject)
        assertTrue(record.valid())
        assertFalse(record.copy(subject = subject.copy(url = "")).valid())
        assertFalse(record.copy(values = TrackingValues(status = 77)).valid())
        assertFalse(record.copy(values = TrackingValues(score = 10.1)).valid())
        assertFalse(record.copy(values = TrackingValues(progress = Double.NaN)).valid())
        assertFalse(record.copy(values = TrackingValues(notes = "x".repeat(32_769))).valid())
        assertFalse(record.copy(values = TrackingValues(tags = List(33) { "tag$it" })).valid())
    }

    @Test fun `pending writes accept opaque numeric identities and reject personal-only fields`() {
        val edit =
            PendingTrackingEdit(
                subject,
                1,
                2,
                3,
                -99,
                "an-account",
                TrackingPatch(TrackingValues(score = 8.0), setOf(TrackingField.SCORE)),
                1,
            )
        assertTrue(edit.valid())
        assertFalse(edit.copy(account = "").valid())
        assertFalse(edit.copy(trackId = 0).valid())
        assertFalse(
            edit.copy(patch = TrackingPatch(TrackingValues(notes = "local"), setOf(TrackingField.NOTES))).valid(),
        )
        assertFalse(edit.copy(patch = edit.patch.copy(fields = emptySet())).valid())
    }

    @Test fun `backup contains personal data but no device bound pending operation`() {
        val record = PersonalTrackingRecord(
            subject,
            TrackingValues(
                progress = 4.5,
                score = 8.2,
                notes = "Private local note",
                tags = listOf("later"),
                repeats = 2,
                volumes = 3.0,
            ),
            changedAt = 50,
        )
        val bytes = ProtoBuf.encodeToByteArray(Backup.serializer(), Backup(personalTracking = listOf(record)))
        val decoded = ProtoBuf.decodeFromByteArray(Backup.serializer(), bytes)
        assertEquals(listOf(record), decoded.personalTracking)
        val old = ProtoBuf.decodeFromByteArray(OldBackup.serializer(), bytes)
        assertTrue(old.isLegacy)
    }

    @Test fun `old backups decode without a personal tracking payload`() {
        val bytes = ProtoBuf.encodeToByteArray(OldBackup.serializer(), OldBackup())
        assertTrue(ProtoBuf.decodeFromByteArray(Backup.serializer(), bytes).personalTracking.isEmpty())
    }

    @Test fun `community distribution requires real bounded counts and a valid average`() {
        assertTrue(CommunityRatings(List(10) { it * 100 }, 8.1).valid())
        assertEquals(4500L, CommunityRatings(List(10) { it * 100 }).total)
        assertFalse(CommunityRatings(List(9) { 0 }).valid())
        assertFalse(CommunityRatings(List(10) { -1 }).valid())
        assertFalse(CommunityRatings(List(10) { 1 }, Double.NaN).valid())
        assertFalse(CommunityRatings(List(10) { 1 }, 11.0).valid())
    }

    @Test fun `manual edits and automatic updates share an ordered gate`() = runTest {
        val entry = TrackingEditGate.entry("test-gate-101")
        val order = mutableListOf<String>()
        val automatic = async {
            entry.mutex.withLock {
                order += "automatic"
                delay(10)
                order += "automatic-finished"
            }
        }
        delay(1)
        val manual = async {
            entry.mutex.withLock {
                entry.generation.incrementAndGet()
                order += "manual"
            }
        }
        automatic.await()
        manual.await()
        assertEquals(listOf("automatic", "automatic-finished", "manual"), order)
        assertEquals(1L, entry.generation.get())
    }

    @Serializable private data class OldBackup(@ProtoNumber(500) val isLegacy: Boolean = true)

    private fun queued(score: Double, progress: Double = 0.0, fields: Set<TrackingField> = setOf(TrackingField.SCORE)) =
        PendingTrackingEdit(
            subject,
            1,
            2,
            3,
            4,
            "fictional-account",
            TrackingPatch(TrackingValues(score = score, progress = progress), fields),
            10,
        )

    @Test fun `acknowledging an old request retains a newer value including undo`() {
        val sent = queued(8.0)
        val changed = queued(6.0).copy(revision = 11)
        assertEquals(changed, changed.acknowledge(sent))
        assertNull(sent.acknowledge(sent))
    }

    @Test fun `acknowledgement removes only confirmed fields from a coalesced request`() {
        val sent = queued(8.0)
        val current = queued(8.0, 9.5, setOf(TrackingField.SCORE, TrackingField.PROGRESS)).copy(revision = 11)
        val remaining = current.acknowledge(sent)!!
        assertEquals(setOf(TrackingField.PROGRESS), remaining.patch.fields)
        assertEquals(9.5, remaining.patch.values.progress, 0.0)
    }

    @Test fun `acknowledgement cannot remove another account or association`() {
        val sent = queued(8.0)
        assertEquals(sent, sent.acknowledge(sent.copy(account = "other-account")))
        assertEquals(sent, sent.acknowledge(sent.copy(remoteId = 55)))
    }

    @Test fun `score edits do not invalidate pending actual playback progress`() {
        val gate = TrackingEditGate.entry("fictional-gate-score")
        val before = gate.progressGeneration.get()
        gate.generation.incrementAndGet()
        assertEquals(before, gate.progressGeneration.get())
        gate.progressGeneration.incrementAndGet()
        assertNotEquals(before, gate.progressGeneration.get())
    }
}

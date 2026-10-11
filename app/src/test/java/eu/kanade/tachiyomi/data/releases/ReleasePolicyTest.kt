package eu.kanade.tachiyomi.data.releases

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupReleaseSubscription
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReleasePolicyTest {
    @Test fun approachingBroadcastBringsCheckForwardWithoutInventingADate() {
        val now = 10 * ReleasePolicy.DAY
        assertEquals(15 * ReleasePolicy.MINUTE, ReleasePolicy.interval(now, now, true, false))
        assertEquals(45 * ReleasePolicy.MINUTE, ReleasePolicy.interval(now, now + ReleasePolicy.HOUR, false, false))
        assertEquals(6 * ReleasePolicy.HOUR, ReleasePolicy.interval(now, null, false, false))
        assertEquals(ReleasePolicy.HOUR, ReleasePolicy.interval(now, now - 8 * ReleasePolicy.HOUR, true, false))
    }

    @Test fun failureBackoffWinsOverFrequentOpeningButExpires() {
        val state = ReleaseCheckState(lastSuccess = 1, nextCheck = 30_000, failures = 2)
        assertFalse(ReleasePolicy.isDue(state, 20_000, foreground = true))
        assertTrue(ReleasePolicy.isDue(state, 30_000, foreground = true))
        assertTrue(ReleasePolicy.isDue(state, 30_000))
    }

    @Test fun foregroundUsesDurableFreshnessAndCompletedSeriesStayInTheRotation() {
        val state = ReleaseCheckState(lastSuccess = 1_000, nextCheck = ReleasePolicy.DAY)
        assertFalse(ReleasePolicy.isDue(state, 2_000, true))
        assertTrue(ReleasePolicy.isDue(state, 1_000 + 10 * ReleasePolicy.MINUTE, true))
        assertEquals(7 * ReleasePolicy.DAY, ReleasePolicy.interval(10_000, null, false, true))
    }

    @Test fun catalogueErrorsAndMalformedPaginationCannotLookLikeAnEmptySchedule() {
        val repository = AiringRepository(mockk())
        assertThrows(IllegalArgumentException::class.java) { repository.parsePage("{\"errors\":[{}]}", 1) }
        assertThrows(IllegalArgumentException::class.java) {
            repository.parsePage("{\"data\":{\"Media\":{\"id\":42,\"airingSchedule\":{\"nodes\":[]}}}}", 1)
        }
        val body = """
            {"data":{"Media":{"id":42,"airingSchedule":{
            "nodes":[{"episode":3,"airingAt":12345}],"pageInfo":{"hasNextPage":true}}}}}
        """.trimIndent()
        val (events, more) = repository.parsePage(body, 7)
        assertTrue(more)
        assertEquals(AiringEvent(7, 3, 12_345_000, 42), events.single())
    }

    @Test fun followSettingsUseStableReferencesAndOldBackupsRemainReadable() {
        val entry = BackupReleaseSubscription("MANGA", 42, "/title", "IGNORE", false, true)
        val bytes = ProtoBuf.encodeToByteArray(Backup.serializer(), Backup(releaseSubscriptions = listOf(entry)))
        assertEquals(entry, ProtoBuf.decodeFromByteArray(Backup.serializer(), bytes).releaseSubscriptions.single())
        val old = ProtoBuf.encodeToByteArray(Backup.serializer(), Backup())
        assertTrue(ProtoBuf.decodeFromByteArray(Backup.serializer(), old).releaseSubscriptions.isEmpty())
    }

    @Test fun cancellationReleasesTheFetchAndCommitLock() = runTest {
        val first = async { ReleaseUpdateGate.withEntry(ReleaseMedium.ANIME, 1) { delay(10_000) } }
        delay(1)
        first.cancel()
        first.join()
        assertEquals(42, ReleaseUpdateGate.withEntry(ReleaseMedium.ANIME, 1) { 42 })
    }

    @Test fun competingFetchAndCommitCannotApplyAnOlderResponseLast() = runTest {
        val committed = mutableListOf<Int>()
        val first = async {
            ReleaseUpdateGate.withEntry(ReleaseMedium.MANGA, 7) {
                delay(100)
                committed += 1
            }
        }
        val second = async { ReleaseUpdateGate.withEntry(ReleaseMedium.MANGA, 7) { committed += 2 } }
        first.await()
        second.await()
        assertEquals(listOf(1, 2), committed)
    }

    @Test fun failedImportReleasesSuppressionAndNestedImportsKeepItActive() = runTest {
        assertFalse(ReleaseRestoreGuard.active)
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking {
                ReleaseRestoreGuard.during {
                    ReleaseRestoreGuard.during { assertTrue(ReleaseRestoreGuard.active) }
                    assertTrue(ReleaseRestoreGuard.active)
                    error("Import interrupted")
                }
            }
        }
        assertFalse(ReleaseRestoreGuard.active)
    }
}

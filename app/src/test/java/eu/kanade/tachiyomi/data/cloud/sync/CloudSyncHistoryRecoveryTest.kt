package eu.kanade.tachiyomi.data.cloud.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import nyanime.telegram.sync.SyncRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.IOException

class CloudSyncHistoryRecoveryTest {
    private var clock = 1_000L
    private val scheduled = mutableMapOf<String, Long>()
    private var enabled = true
    private val recovery =
        CloudSyncHistoryRecovery({ clock }, { scheduled[it] ?: 0 }, { k, v -> scheduled[k] = v }, { enabled })
    private val ref = SyncRef("manga-history", 73, "/entry/1", "/chapter/2")
    private val item = RecoveredSyncItem(ref.item, PortableItem("Chapter 2", 2.0, 3))

    @Test fun exactIdentityAndSharedCacheRepairSeveralHistoryEntriesWithOneRequest() = runTest {
        var calls = 0
        val next = item.copy(url = "/chapter/3", metadata = item.metadata.copy(name = "Chapter 3"))
        assertEquals(
            item,
            recovery.recover(ref) {
                calls++
                listOf(item, next)
            },
        )
        assertEquals(next, recovery.recover(ref.copy(item = next.url)) { error("Reuse the same title list") })
        assertNull(recovery.recover(ref.copy(item = "/chapter/4")) { error("Never guess by number/name") })
        assertEquals(1, calls)
    }

    @Test fun duplicateReferencesAndInvalidMetadataRemainUnresolved() = runTest {
        assertNull(recovery.recover(ref) { listOf(item, item.copy(metadata = item.metadata.copy(name = "Other"))) })
        recovery.beginPass()
        assertNull(
            recovery.recover(ref.copy(title = "/entry/2")) {
                listOf(item.copy(metadata = item.metadata.copy(name = "")))
            },
        )
    }

    @Test fun failureAndRestartKeepDurableBackoffWithoutBlockingOtherTitles() = runTest {
        assertNull(recovery.recover(ref) { throw IOException() })
        val restarted =
            CloudSyncHistoryRecovery({ clock }, { scheduled[it] ?: 0 }, { k, v -> scheduled[k] = v }, { true })
        assertNull(restarted.recover(ref) { error("Respect the saved backoff") })
        assertEquals(item, restarted.recover(ref.copy(title = "/entry/2")) { listOf(item) })
        clock += 600_000
        restarted.beginPass()
        assertEquals(item, restarted.recover(ref) { listOf(item) })
    }

    @Test fun videoAndMangaShareOneNetworkRequestPerProjectionPass() = runTest {
        assertEquals(item, recovery.recover(ref) { listOf(item) })
        val video = ref.copy(kind = "anime-history")
        assertNull(recovery.recover(video) { error("One request shared across media") })
        recovery.beginPass()
        assertEquals(item, recovery.recover(video) { listOf(item) })
    }

    @Test fun offlineAndIncognitoPolicyPreventRequestsAndUsingStaleCachedLists() = runTest {
        enabled = false
        assertNull(recovery.recover(ref) { error("No requests allowed") })
        assertEquals(0, scheduled.size)
        enabled = true
        assertEquals(item, recovery.recover(ref) { listOf(item) })
        enabled = false
        assertNull(recovery.recover(ref) { error("No cache use across private policy") })
    }

    @Test fun timeoutLeavesHistoryPendingAndCancellationPropagates() = runTest {
        assertNull(
            recovery.recover(ref) {
                delay(20_000)
                listOf(item)
            },
        )
        recovery.beginPass()
        try {
            recovery.recover(ref.copy(title = "/entry/2")) { throw CancellationException("Cancelled") }
            error("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun enteringPrivateModeDuringTheRequestDoesNotApplyOrCacheItsResponse() = runTest {
        assertNull(
            recovery.recover(ref) {
                enabled = false
                listOf(item)
            },
        )
        enabled = true
        assertNull(recovery.recover(ref) { error("The private response was not cached") })
    }
}

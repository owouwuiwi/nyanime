package eu.kanade.tachiyomi.data.extensionstore

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StoreInstallCoordinatorTest {
    @Test fun `different catalogue cards cannot install the same package twice`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val coordinator = StoreInstallCoordinator(scope)
            val finish = CompletableDeferred<Unit>()
            var started = 0
            val first = coordinator.submit("test.extension.media") {
                started++
                finish.await()
            }
            val duplicate = coordinator.submit("test.extension.media") { started++ }
            yield()
            assertSame(first, duplicate)
            assertEquals(1, started)
            finish.complete(Unit)
            first.join()
            coordinator.submit("test.extension.media") { started++ }.join()
            assertEquals(2, started)
        } finally {
            scope.cancel()
        }
    }

    @Test fun `cancelling a transfer permits retry and rejects its late status`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val coordinator = StoreInstallCoordinator(scope)
            val finish = CompletableDeferred<Unit>()
            val old = coordinator.submit("test.extension.media") { finish.await() }
            yield()
            assertTrue(coordinator.isCurrent("test.extension.media", old))
            coordinator.cancel("test.extension.media")
            val retry = coordinator.submit("test.extension.media") { finish.await() }
            assertNotSame(old, retry)
            old.join()
            assertFalse(coordinator.isCurrent("test.extension.media", old))
            assertTrue(coordinator.isCurrent("test.extension.media", retry))
            finish.complete(Unit)
            retry.join()
        } finally {
            scope.cancel()
        }
    }

    @Test fun `one cancelled package does not stop another transfer`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val coordinator = StoreInstallCoordinator(scope)
            val finish = CompletableDeferred<Unit>()
            val first = coordinator.submit("test.extension.first") { finish.await() }
            val second = coordinator.submit("test.extension.second") { finish.await() }
            yield()
            coordinator.cancel("test.extension.first")
            first.join()
            assertTrue(coordinator.isCurrent("test.extension.second", second))
            finish.complete(Unit)
            second.join()
        } finally {
            scope.cancel()
        }
    }
}

package eu.kanade.tachiyomi.extension

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ExtensionInventoryCacheTest {
    @Test fun `concurrent family loads share one complete scan`() {
        val cache = ExtensionInventoryCache<String>({ 0 })
        val scans = AtomicInteger()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = pool.invokeAll(
                List(12) {
                    Callable {
                        cache.read {
                            scans.incrementAndGet()
                            listOf("example.extension")
                        }
                    }
                },
            ).map { it.get() }
            assertEquals(1, scans.get())
            assertTrue(results.all { it.items == listOf("example.extension") && !it.unavailable })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun `temporary platform failures retain the complete last scan and back off`() {
        var now = 0L
        val cache = ExtensionInventoryCache<String>({ now })
        cache.read { listOf("example.extension") }
        cache.invalidate()
        var attempts = 0
        fun failed() = cache.read {
            attempts++
            throw IllegalStateException("Temporary platform failure")
        }
        assertEquals(listOf("example.extension"), failed().items)
        now = 500
        assertTrue(failed().unavailable)
        assertEquals(1, attempts)
        now = 1_000
        failed()
        now = 2_000
        failed()
        assertEquals(2, attempts)
        now = 3_000
        assertFalse(cache.read { listOf("example.updated") }.unavailable)
    }

    @Test fun `a failed initial scan is distinct from an authoritative empty scan`() {
        val cache = ExtensionInventoryCache<String>({ 0 })
        assertTrue(cache.read { error("Unavailable") }.unavailable)
        cache.invalidate()
        val empty = cache.read { emptyList() }
        assertFalse(empty.unavailable)
        assertTrue(empty.items.isEmpty())
    }

    @Test fun `persisted candidates remain available when the first scan fails`() {
        val cache = ExtensionInventoryCache({ 0 }, listOf("example.persisted"))
        val result = cache.read { throw IllegalStateException("Unavailable") }
        assertEquals(listOf("example.persisted"), result.items)
        assertTrue(result.unavailable)
    }
}

package eu.kanade.tachiyomi.data.track

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TrackerTokenSessionTest {
    @Test
    fun `concurrent expired requests renew once and use the fresh token`() {
        val renewals = AtomicInteger()
        val writes = mutableListOf<Int?>()
        val session = TrackerTokenSession(0) { writes += it }
        val ready = CountDownLatch(8)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = List(8) {
                executor.submit<Int> {
                    ready.countDown()
                    check(ready.await(3, TimeUnit.SECONDS))
                    session.valid({ it == 0 }) {
                        renewals.incrementAndGet()
                        1
                    }
                }
            }
            assertEquals(List(8) { 1 }, results.map { it.get(3, TimeUnit.SECONDS) })
            assertEquals(1, renewals.get())
            assertEquals(listOf(1), writes)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `failed renewal cannot send an expired token or persist broken data`() {
        val writes = mutableListOf<Int?>()
        val session = TrackerTokenSession(0) { writes += it }
        assertThrows(IOException::class.java) {
            session.valid({ it == 0 }) { throw IllegalArgumentException("Invalid response") }
        }
        assertEquals(emptyList<Int?>(), writes)
        assertEquals(1, session.valid({ it == 0 }) { 1 })
    }

    @Test
    fun `logout wins over a renewal already in flight`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writes = mutableListOf<Int?>()
        val session = TrackerTokenSession(0) { writes += it }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val renewal = executor.submit<Int> {
                session.valid({ it == 0 }) {
                    entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                    1
                }
            }
            check(entered.await(3, TimeUnit.SECONDS))
            val logout = executor.submit { session.set(null) }
            release.countDown()
            assertEquals(1, renewal.get(3, TimeUnit.SECONDS))
            logout.get(3, TimeUnit.SECONDS)
            assertEquals(listOf(1, null), writes)
            assertThrows(IOException::class.java) { session.valid({ false }) { 2 } }
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}

package eu.kanade.tachiyomi.network.interceptor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class HostChallengeCoordinatorTest {
    @Test
    fun `concurrent requests share successful recovery`() {
        runWave(fails = false)
    }

    @Test
    fun `concurrent requests share failure instead of opening browsers repeatedly`() {
        runWave(fails = true)
    }

    private fun runWave(fails: Boolean) {
        val coordinator = HostChallengeCoordinator()
        val ready = CountDownLatch(8)
        val attempts = AtomicInteger()
        val cleared = AtomicBoolean()
        val leases = List(8) { coordinator.acquire("example.test") }
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = leases.map { lease ->
                executor.submit<Boolean> {
                    lease.use {
                        val (_, generation) = it.request({ false }) {
                            ready.countDown()
                            check(ready.await(3, TimeUnit.SECONDS))
                        }
                        try {
                            it.recover(generation, { false }, cleared::get) {
                                attempts.incrementAndGet()
                                if (fails) throw IOException("Verification unavailable")
                                cleared.set(true)
                            }
                            true
                        } catch (_: IOException) {
                            false
                        }
                    }
                }
            }
            assertEquals(List(8) { !fails }, results.map { it.get(5, TimeUnit.SECONDS) })
            assertEquals(1, attempts.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `nested same host request can recover without deadlocking its parent`() {
        val coordinator = HostChallengeCoordinator()
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit {
                coordinator.acquire("example.test").use { outer ->
                    outer.request({ false }) {
                        coordinator.acquire("example.test").use { inner ->
                            val (_, generation) = inner.request({ false }) { Unit }
                            inner.recover(generation, { false }, { false }) { Unit }
                        }
                    }
                }
            }.get(3, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `unrelated hosts are independent and cancelled requests do not run`() {
        val coordinator = HostChallengeCoordinator()
        coordinator.acquire("one.test").use { first ->
            first.request({ false }) {
                coordinator.acquire("two.test").use { second ->
                    val (_, generation) = second.request({ false }) { Unit }
                    second.recover(generation, { false }, { false }) { Unit }
                    assertThrows(InterruptedIOException::class.java) {
                        second.request({ true }) { error("Must not start a cancelled request") }
                    }
                }
            }
        }
    }
}

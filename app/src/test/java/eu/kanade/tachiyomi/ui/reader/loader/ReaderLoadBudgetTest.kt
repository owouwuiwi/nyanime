package eu.kanade.tachiyomi.ui.reader.loader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderLoadBudgetTest {
    @Test
    fun `adjacent chapter prefetch leaves a slot for the visible page`() = runTest {
        val budget = ReaderLoadBudget()
        val release = CompletableDeferred<Unit>()
        var background = 0
        val jobs = List(6) {
            launch {
                budget.load(foreground = false) {
                    background++
                    try {
                        release.await()
                    } finally {
                        background--
                    }
                }
            }
        }
        runCurrent()
        assertEquals(2, background)
        val visible = async { budget.load(foreground = true) { "visible" } }
        runCurrent()
        assertTrue(visible.isCompleted)
        assertEquals("visible", visible.await())
        release.complete(Unit)
        jobs.joinAll()
        assertEquals(0, background)
    }

    @Test
    fun `rapid page changes never exceed the shared transfer budget`() = runTest {
        val budget = ReaderLoadBudget()
        var active = 0
        var peak = 0
        val jobs = List(30) { index ->
            launch {
                budget.load(foreground = index % 3 == 0) {
                    active++
                    peak = maxOf(peak, active)
                    try {
                        delay(10)
                    } finally {
                        active--
                    }
                }
            }
        }
        jobs.joinAll()
        assertEquals(3, peak)
        assertEquals(0, active)
    }

    @Test
    fun `cancelled prefetch releases permits and never starts a superseded request`() = runTest {
        val budget = ReaderLoadBudget()
        val release = CompletableDeferred<Unit>()
        val running = List(2) { launch { budget.load(false) { release.await() } } }
        runCurrent()
        val cancelled = launch { budget.load(false) { error("Superseded page must not load") } }
        runCurrent()
        cancelled.cancelAndJoin()
        running.forEach { it.cancelAndJoin() }
        assertEquals(7, budget.load(false) { 7 })
        assertEquals(8, budget.load(true) { 8 })
    }

    @Test
    fun `the same image shared across chapters is downloaded only once`() = runTest {
        val budget = ReaderLoadBudget()
        var cached = false
        var requests = 0
        val jobs = List(3) {
            launch {
                budget.load(true) {
                    budget.image("shared-image") {
                        if (!cached) {
                            requests++
                            delay(10)
                            cached = true
                        }
                    }
                }
            }
        }
        jobs.joinAll()
        assertEquals(1, requests)
    }
}

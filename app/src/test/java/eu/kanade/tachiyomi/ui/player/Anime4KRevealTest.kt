package eu.kanade.tachiyomi.ui.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Anime4KRevealTest {
    private val selection = MutableStateFlow(Anime4KSelection(Anime4KProfile.Off, Anime4KMode.Off))
    private val point = Anime4KRevealOrigin(2_100f, 90f)
    private var boundaries = 0
    private fun enable() {
        selection.value = Anime4KSelection(Anime4KProfile.Smart, Anime4KMode.ModeA)
    }
    private fun disable() {
        selection.value = Anime4KSelection(Anime4KProfile.Off, Anime4KMode.Off)
    }

    @Test
    fun `the ordinary Smart action runs once and completing decoration keeps its quality`() = runTest {
        val reveal = Anime4KReveal(this, selection) { boundaries++ }
        var actions = 0
        reveal.toggle(point, true, true) {
            actions++
            enable()
        }
        runCurrent()
        val frame = requireNotNull(reveal.state.value)
        assertEquals(point, frame.origin)
        assertEquals(1, actions)
        assertTrue(reveal.measuringSuppressed)
        reveal.complete(frame.id)
        assertNull(reveal.state.value)
        assertEquals(Anime4KMode.ModeA, selection.value.mode)
        assertEquals(2, boundaries)
    }

    @Test
    fun `asynchronous analysis does not animate before Smart actually applies`() = runTest {
        val reveal = Anime4KReveal(this, selection) { boundaries++ }
        reveal.toggle(point, true, true) { selection.value = Anime4KSelection(Anime4KProfile.Smart, Anime4KMode.Off) }
        runCurrent()
        advanceTimeBy(400)
        assertNull(reveal.state.value)
        assertEquals(0, boundaries)
        enable()
        runCurrent()
        assertNotNull(reveal.state.value)
        reveal.finish()
        assertEquals(2, boundaries)
    }

    @Test
    fun `later normal activation after preparation timeout never starts an automatic cloud`() = runTest {
        val reveal = Anime4KReveal(this, selection) {}
        reveal.toggle(point, true, true) { selection.value = Anime4KSelection(Anime4KProfile.Smart, Anime4KMode.Off) }
        advanceTimeBy(Anime4KContentAnalysis.DEADLINE_MILLIS + 501)
        runCurrent()
        enable()
        runCurrent()
        assertNull(reveal.state.value)
        assertFalse(reveal.measuringSuppressed)
        assertEquals(Anime4KMode.ModeA, selection.value.mode)
    }

    @Test
    fun `off during preparation cancels the waiting cloud`() = runTest {
        val reveal = Anime4KReveal(this, selection) {}
        reveal.toggle(point, true, true) { selection.value = Anime4KSelection(Anime4KProfile.Smart, Anime4KMode.Off) }
        runCurrent()
        reveal.toggle(point, false, true, ::disable)
        advanceTimeBy(2_000)
        runCurrent()
        assertNull(reveal.state.value)
        assertEquals(Anime4KProfile.Off, selection.value.profile)
    }

    @Test
    fun `a new request ignores a delayed completion belonging to an earlier cloud`() = runTest {
        val reveal = Anime4KReveal(this, selection) {}
        reveal.toggle(point, true, true, ::enable)
        runCurrent()
        val old = requireNotNull(reveal.state.value).id
        reveal.toggle(point, false, true, ::disable)
        assertTrue(requireNotNull(reveal.state.value).exiting)
        reveal.toggle(point, true, true, ::enable)
        runCurrent()
        val new = requireNotNull(reveal.state.value).id
        assertTrue(old != new)
        reveal.complete(old)
        assertEquals(new, reveal.state.value?.id)
        reveal.complete(new)
        assertNull(reveal.state.value)
    }

    @Test
    fun `reduced motion changes Smart without starting or suppressing any animation`() = runTest {
        val reveal = Anime4KReveal(this, selection) { boundaries++ }
        reveal.toggle(point, true, false, ::enable)
        runCurrent()
        assertNull(reveal.state.value)
        assertFalse(reveal.measuringSuppressed)
        assertEquals(0, boundaries)
        assertEquals(Anime4KMode.ModeA, selection.value.mode)
    }

    @Test
    fun `an absent or invalid origin still performs the requested Smart change`() = runTest {
        val reveal = Anime4KReveal(this, selection) {}
        reveal.toggle(Anime4KRevealOrigin(Float.NaN, 0f), true, true, ::enable)
        runCurrent()
        assertNull(reveal.state.value)
        assertEquals(Anime4KMode.ModeA, selection.value.mode)
        assertFalse(Anime4KRevealOrigin(0f, Float.POSITIVE_INFINITY).valid)
        assertFalse(Anime4KRevealOrigin(2_000_000f, 0f).valid)
        assertTrue(point.valid)
    }

    @Test
    fun `an absent chrome cannot leave the effect or telemetry suppression alive indefinitely`() = runTest {
        val reveal = Anime4KReveal(this, selection) { boundaries++ }
        reveal.toggle(point, true, true, ::enable)
        runCurrent()
        assertNotNull(reveal.state.value)
        advanceTimeBy(Anime4KReveal.DURATION_MILLIS * 10 + 251)
        runCurrent()
        assertNull(reveal.state.value)
        assertFalse(reveal.measuringSuppressed)
        assertEquals(2, boundaries)
    }

    @Test
    fun `real presentation pressure ends decoration without reducing the applied preset`() = runTest {
        val reveal = Anime4KReveal(this, selection) {}
        reveal.observePerformance(Anime4KPerformanceSample(0, 0, 0, 0, 8.0))
        reveal.toggle(point, true, true, ::enable)
        runCurrent()
        reveal.observePerformance(Anime4KPerformanceSample(500, 2, 0, 0, 8.0))
        assertNotNull(reveal.state.value)
        reveal.observePerformance(Anime4KPerformanceSample(1_000, 4, 0, 0, 8.0))
        assertNull(reveal.state.value)
        assertEquals(Anime4KMode.ModeA, selection.value.mode)
    }

    @Test
    fun `decoder losses do not get misclassified as decorative rendering pressure`() = runTest {
        val reveal = Anime4KReveal(this, selection) {}
        reveal.observePerformance(Anime4KPerformanceSample(0, 0, 0, 0, 8.0))
        reveal.toggle(point, true, true, ::enable)
        runCurrent()
        reveal.observePerformance(Anime4KPerformanceSample(500, 2, 2, 0, 8.0))
        reveal.observePerformance(Anime4KPerformanceSample(1_000, 4, 4, 0, 8.0))
        assertNotNull(reveal.state.value)
        reveal.finish()
    }

    @Test
    fun `drops accumulated before the cloud starts cannot make the new cloud disappear`() = runTest {
        val reveal = Anime4KReveal(this, selection) {}
        reveal.observePerformance(Anime4KPerformanceSample(0, 0, 0, 0, 8.0))
        reveal.toggle(point, true, true, ::enable)
        runCurrent()
        reveal.observePerformance(Anime4KPerformanceSample(500, 12, 0, 0, 8.0))
        assertNotNull(reveal.state.value)
        reveal.observePerformance(Anime4KPerformanceSample(1_000, 12, 0, 0, 8.0))
        assertNotNull(reveal.state.value)
        reveal.finish()
    }
}

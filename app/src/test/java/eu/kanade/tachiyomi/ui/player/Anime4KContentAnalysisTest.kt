package eu.kanade.tachiyomi.ui.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Anime4KContentAnalysisTest {
    @Test
    fun `unvalidated analysis is not enabled by default`() {
        assertFalse(Anime4KContentAnalysis.DEFAULT_ENABLED)
    }

    @Test
    fun `black white flat and undersized frames cannot choose a treatment`() {
        for (colour in listOf(0xff000000.toInt(), 0xffffffff.toInt(), 0xff777777.toInt())) {
            assertNull(Anime4KContentAnalysis.features(IntArray(256 * 144) { colour }, 256, 144))
        }
        assertNull(Anime4KContentAnalysis.features(IntArray(10), 10, 1))
        assertNull(Anime4KContentAnalysis.features(IntArray(10), 256, 144))
    }

    @Test
    fun `agreement selects treatment without weakening its HQ ceiling`() {
        val blurred = Anime4KImageFeatures(0.15, 16.0, 0.5, 0.3, 2500.0)
        val aliased = Anime4KImageFeatures(0.12, 42.0, 2.2, 0.2, 2800.0)
        assertNull(Anime4KContentAnalysis.assess(listOf(blurred)).treatment)
        assertNull(Anime4KContentAnalysis.assess(listOf(blurred, aliased)).treatment)
        val agreed = Anime4KContentAnalysis.assess(listOf(blurred, blurred))
        assertEquals(Anime4KMode.ModeA, agreed.treatment)
        assertTrue(agreed.confidence >= 0.85)
        assertEquals(Anime4KMode.ModeB, Anime4KContentAnalysis.assess(listOf(aliased, aliased)).treatment)
        assertNull(Anime4KContentAnalysis.assess(listOf(blurred.copy(variance = Double.NaN), blurred)).treatment)
    }

    @Test
    fun `actual degraded image features distinguish broad transitions from crisp edges`() {
        val width = 256
        val height = 144
        val sharp = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            val value = if ((x / 24 + y / 20) % 2 == 0) 40 else 210
            0xff000000.toInt() or (value * 0x010101)
        }
        val blurred = IntArray(sharp.size) { i ->
            var value = 0
            for (offset in -3..3) {
                val x = (i % width + offset).coerceIn(0, width - 1)
                value += sharp[i / width * width + x] and 255
            }
            0xff000000.toInt() or ((value / 7) * 0x010101)
        }
        val crispFeatures = requireNotNull(Anime4KContentAnalysis.features(sharp, width, height))
        val softFeatures = requireNotNull(Anime4KContentAnalysis.features(blurred, width, height))
        assertTrue(softFeatures.edgeStrength < crispFeatures.edgeStrength)
        assertTrue(softFeatures.curvature < crispFeatures.curvature)
        // This verifies measured degradation, not an unvalidated claim that a family looks better.
        assertTrue(crispFeatures.variance > softFeatures.variance)
    }

    @Test
    fun `startup probe is bounded and does not treat static duplicates as agreement`() = runBlocking {
        var calls = 0
        val result = Anime4KContentProbe.collect {
            calls++
            Anime4KImageSample(
                256,
                144,
                IntArray(256 * 144) { i ->
                    val value = if (i % 256 < 128) 40 else 200
                    0xff000000.toInt() or (value * 0x010101)
                },
            )
        }
        assertEquals(3, calls)
        assertNull(result.assessment.treatment)
        assertEquals(Anime4KContentState.Uncertain, result.state)
    }

    @Test
    fun `noise and dense texture are measured on images without forcing a family`() {
        val width = 256
        val height = 144
        val random = kotlin.random.Random(7)
        fun colour(value: Int) = 0xff000000.toInt() or (value * 0x010101)
        val clean = IntArray(width * height) { i -> colour(if ((i % width) / 24 % 2 == 0) 50 else 200) }
        val noisy =
            IntArray(clean.size) { i -> colour(((clean[i] and 255) + random.nextInt(-12, 13)).coerceIn(0, 255)) }
        val texture = IntArray(clean.size) { i ->
            colour(if ((i % width / 2 + i / width / 2) % 2 == 0) 50 else 200)
        }
        val cleanFeatures = requireNotNull(Anime4KContentAnalysis.features(clean, width, height))
        val noiseFeatures = requireNotNull(Anime4KContentAnalysis.features(noisy, width, height))
        val textureFeatures = requireNotNull(Anime4KContentAnalysis.features(texture, width, height))
        assertTrue(noiseFeatures.flatNoise > cleanFeatures.flatNoise + 1.0)
        assertTrue(textureFeatures.edgeDensity > cleanFeatures.edgeDensity)
        assertNull(Anime4KContentAnalysis.assess(listOf(textureFeatures, textureFeatures)).treatment)
    }

    @Test
    fun `timeout falls back and external cancellation is not swallowed`() = runBlocking {
        val timeout = Anime4KContentProbe.collect { awaitCancellation() }
        assertEquals(Anime4KContentState.Unavailable, timeout.state)
        val pending = async { Anime4KContentProbe.collect { awaitCancellation() } }
        delay(10)
        pending.cancel()
        assertThrows(CancellationException::class.java) { runBlocking { pending.await() } }
        Unit
    }
}

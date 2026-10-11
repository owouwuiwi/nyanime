package eu.kanade.tachiyomi.ui.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Anime4KTelemetryTest {
    private val valid = """
        {"version":1,"token":"42","sequence":1,"playing":true,
        "renderTimeMillis":2.5,"outputDrops":4294967296,"extra":"ignored"}
    """.trimIndent()

    @Test
    fun `native snapshot keeps long counters and timing units`() {
        val sample = Anime4KTelemetry.parse(valid)!!.sample(123)
        assertEquals(4294967296L, sample.outputDroppedFrames)
        assertEquals(2.5, sample.renderTimeMillis)
        assertEquals(123L, sample.timestampMillis)
        assertTrue(sample.playing)
        assertNull(sample.decoderDroppedFrames)
    }

    @Test
    fun `malformed oversized and future protocol snapshots are rejected`() {
        for (value in listOf(
            "",
            "{}",
            valid.replace("\"version\":1", "\"version\":2"),
            valid.replace("\"sequence\":1", "\"sequence\":-1"),
            " ".repeat(4097),
        )) {
            assertNull(Anime4KTelemetry.parse(value))
        }
    }

    @Test
    fun `invalid readings remain unknown`() {
        val sample = Anime4KTelemetry(1, "42", 1, false, -1, -2, -3, -4, Double.POSITIVE_INFINITY, -10.0).sample(0)
        assertFalse(sample.playing)
        assertNull(sample.renderTimeMillis)
        assertNull(sample.redrawTimeMillis)
        assertNull(sample.outputDroppedFrames)
        assertNull(sample.decoderDroppedFrames)
        assertNull(sample.delayedFrames)
        assertNull(sample.mistimedFrames)
    }

    @Test
    fun `session rejects replay and previous episode or preset packets`() {
        val session = Anime4KTelemetrySession("42", 0)
        val packet = Anime4KTelemetry.parse(valid)!!
        assertNull(session.accept(packet.copy(token = "41"), 1000))
        assertNotNull(session.accept(packet, 1000))
        assertNull(session.accept(packet, 2000))
        assertNull(session.accept(packet.copy(sequence = 0), 2000))
        assertNotNull(session.accept(packet.copy(sequence = 2), 2000))
    }

    @Test
    fun `fallback is throttled before JNI reads and yields to the native bridge`() {
        val session = Anime4KTelemetrySession("42", 0)
        assertFalse(session.shouldSampleFallback(1000))
        assertTrue(session.shouldSampleFallback(2500))
        assertFalse(session.shouldSampleFallback(2600))
        assertTrue(session.shouldSampleFallback(3500))
        session.accept(Anime4KTelemetry.parse(valid)!!, 3600)
        assertFalse(session.shouldSampleFallback(5000))
        assertTrue(session.shouldSampleFallback(6100))
    }
}

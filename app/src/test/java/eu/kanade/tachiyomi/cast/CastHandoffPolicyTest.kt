package eu.kanade.tachiyomi.cast

import eu.kanade.tachiyomi.data.cast.CastHandoffPolicy
import eu.kanade.tachiyomi.data.cast.CastPlayback
import eu.kanade.tachiyomi.data.cast.DlnaCapabilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CastHandoffPolicyTest {
    @Test
    fun aNewEpisodeNeverInheritsThePreviousVideosClock() {
        assertEquals(25_000, CastHandoffPolicy.startPosition(null, true, 25_000, false, false, 900_000))
        assertEquals(0, CastHandoffPolicy.startPosition(null, true, 25_000, true, false, 900_000))
        assertEquals(25_000, CastHandoffPolicy.startPosition(null, true, 25_000, true, true, 900_000))
        assertEquals(900_000, CastHandoffPolicy.startPosition(null, false, 25_000, false, false, 900_000))
    }

    @Test
    fun qualityChangesKeepTheRemoteClockAndBufferingDoesNotResetProgress() {
        assertEquals(600_000, CastHandoffPolicy.startPosition(600_000, true, 0, false, false, 0))
        val previous = CastPlayback(positionMs = 600_000, durationMs = 1_200_000)
        val buffering = CastHandoffPolicy.observation(previous, CastPlayback(buffering = true))
        assertEquals(previous.positionMs, buffering.positionMs)
        assertEquals(previous.durationMs, buffering.durationMs)
        val seekToStart = CastHandoffPolicy.observation(previous, CastPlayback(positionMs = 0, paused = false))
        assertEquals(0, seekToStart.positionMs)
        val finished = CastHandoffPolicy.observation(previous, CastPlayback(finished = true))
        assertEquals(1_200_000, finished.positionMs)
    }

    @Test
    fun brightnessUsesTheReceiversRangeAndIsAbsentWhenTheActionIsMissing() {
        val scpd = """
            <scpd><actionList><action><name>GetBrightness</name></action>
            <action><name>SetBrightness</name></action></actionList><serviceStateTable>
            <stateVariable><name>Brightness</name><allowedValueRange>
            <minimum>10</minimum><maximum>210</maximum><step>5</step>
            </allowedValueRange></stateVariable></serviceStateTable></scpd>
        """.trimIndent()
        val control = DlnaCapabilities.parse(scpd).brightness!!
        assertEquals(110, control.fromFraction(0.5f))
        assertEquals(210, control.fromFraction(2f))
        assertEquals(10, control.fromFraction(-2f))
        assertEquals(0.5f, control.toFraction(110))
        assertNull(DlnaCapabilities.parse(scpd).volume)
        assertNull(DlnaCapabilities.parse(scpd.replace("SetBrightness", "SomethingElse")).brightness)
    }
}

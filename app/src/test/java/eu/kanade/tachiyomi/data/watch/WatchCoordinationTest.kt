package eu.kanade.tachiyomi.data.watch

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import eu.kanade.tachiyomi.data.watch.testRoomText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class WatchCoordinationTest {
    @Test
    fun smoothCorrectionConvergesWithoutSeekingOrSpeedSteps() {
        val corrector = WatchDriftCorrector()
        var local = 98.2
        var remote = 100.0
        var speed = 1.0
        repeat(600) { tick ->
            val correction = corrector.correct(local, remote, 1.0, false, true, tick * 250L)
            assertNull(correction.seek)
            assertTrue(abs(correction.speed - speed) <= 0.0031)
            assertTrue(correction.speed in 0.97..1.03)
            speed = correction.speed
            local += 0.25 * speed
            remote += 0.25
        }
        assertTrue(abs(local - remote) < 1.0)
    }

    @Test
    fun jitterDoesNotKeepChangingSpeedAndPersistentLargeErrorsSeek() {
        val corrector = WatchDriftCorrector()
        repeat(100) { tick ->
            val correction = corrector.correct(
                100.0,
                100.0 +
                    if (tick % 2 ==
                        0
                    ) {
                        0.2
                    } else {
                        -0.2
                    },
                1.5,
                false,
                true,
                tick * 250L,
            )
            assertEquals(1.5, correction.speed)
            assertNull(correction.seek)
        }
        corrector.reset()
        assertNull(corrector.correct(10.0, 15.0, 1.0, false, true, 0).seek)
        assertNull(corrector.correct(10.0, 15.0, 1.0, false, true, 1000).seek)
        assertEquals(15.0, corrector.correct(10.0, 15.0, 1.0, false, true, 3000).seek)
        assertEquals(1.0, corrector.correct(13.0, 13.0, 1.0, true, true, 2000).speed)
    }

    @Test
    fun aBriefNetworkDelayDoesNotCauseRepeatedCacheInvalidatingSeeks() {
        val corrector = WatchDriftCorrector()
        repeat(100) { tick ->
            assertNull(corrector.correct(100.0, 102.5, 1.0, false, true, tick * 250L).seek)
        }
        repeat(100) { tick ->
            assertNull(corrector.correct(100.0, 100.8, 1.0, true, true, tick * 250L).seek)
        }
        assertEquals(110.0, corrector.correct(100.0, 110.0, 1.0, false, true, 30_000).seek)
    }

    @Test
    fun everyInterruptionInvalidatesTheSharedStartDeadline() {
        val gate = WatchStartGate()
        assertFalse(gate.update(true, true, 0))
        assertFalse(gate.update(true, true, 1000))
        assertEquals(3000L, gate.deadline)
        assertFalse(gate.update(false, true, 2500))
        assertNull(gate.deadline)
        assertFalse(gate.update(true, true, 3000))
        assertFalse(gate.update(true, true, 4000))
        assertFalse(gate.update(true, true, 5999))
        assertTrue(gate.update(true, true, 6000))
        assertFalse(gate.update(true, false, 6100))
        assertNull(gate.deadline)
    }

    @Test
    fun prebufferGateUsesTheSlowestCacheAndHasABoundedFallback() {
        val gate = WatchPrebufferGate()
        assertTrue(gate.waiting(true, true, true, 0, listOf(15, 3)))
        assertFalse(gate.waiting(true, true, true, 15_000, listOf(15, null)))
        gate.reset()
        assertTrue(gate.waiting(true, true, true, 30_000, listOf(15, null)))
        assertTrue(gate.waiting(true, true, true, 33_999, listOf(15, null)))
        assertFalse(gate.waiting(true, true, true, 34_000, listOf(15, null)))
        gate.reset()
        assertTrue(gate.waiting(true, true, true, 40_000, listOf(3, 15)))
        assertFalse(gate.waiting(true, true, true, 40_100, listOf(15, 15)))
        gate.reset()
        assertTrue(gate.waiting(true, true, true, 50_000, listOf(0, 0)))
        assertFalse(gate.waiting(true, true, true, 65_000, listOf(0, 0)))
        assertFalse(gate.waiting(false, true, true, 40_200, listOf(0, 0)))
        assertFalse(gate.waiting(true, false, true, 40_300, listOf(0, 0)))
    }

    @Test
    fun readinessFlappingCannotRestartTheInitialCacheDeadline() {
        val gate = WatchPrebufferGate()
        assertTrue(gate.waiting(true, true, true, 0, listOf(0)))
        repeat(14) { second ->
            assertFalse(gate.waiting(true, true, false, second * 1000L + 500, listOf(0)))
            assertTrue(gate.waiting(true, true, true, second * 1000L + 750, listOf(0)))
        }
        assertFalse(gate.waiting(true, true, true, 15_001, listOf(0)))
    }

    @Test
    fun httpsRoomLinksAcceptOnlyTheVerifiedRouteAndKeepLegacyInvitations() {
        val now = 1_800_000_000_000L
        assertEquals("00123456", WatchInvite.codeFromLink("https://owouwuiwi.github.io/watch/#00123456", now))
        assertEquals("00123456", WatchInvite.codeFromLink("nyanime://watch/v1#00123456", now))
        listOf(
            "https://example.invalid/watch/#00123456",
            "https://owouwuiwi.github.io/other/#00123456",
            "https://owouwuiwi.github.io/watch/?x=1#00123456",
            "https://user@owouwuiwi.github.io/watch/#00123456",
            "https://owouwuiwi.github.io:443/watch/#00123456",
            "https://owouwuiwi.github.io/watch/#001234567",
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { WatchInvite.codeFromLink(invalid, now) }
        }
    }

    @Test
    fun linksAndQrPreserveTheExactInvitationWithoutNetworking() {
        val identity = WatchIdentity()
        val now = 1_800_000_000_000L
        val invitation = WatchInvite.create(identity.publicKey, now)
        val link = invitation.link()
        assertEquals(invitation.encode(), WatchInvite.codeFromLink(link, now))
        val qr = WatchQr.encode(link)
        val pixels = IntArray(qr.width * qr.height) {
            if (qr[it % qr.width, it / qr.width]) 0xff000000.toInt() else 0xffffffff.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(qr.width, qr.height, pixels)))
        val decoded = QRCodeReader().decode(bitmap, mapOf(DecodeHintType.PURE_BARCODE to true)).text
        assertEquals(link, decoded)
        assertEquals(invitation.secret, WatchInvite.parse(decoded, now).secret)
        identity.clear()
    }

    @Test
    fun shortRoomLinkShareTextAndQrAllUseTheSameEightDigits() {
        val code = "01234567"
        val link = WatchShortRooms.link(code)
        assertEquals(code, WatchInvite.codeFromLink(link, 1_800_000_000_000L))
        val shared = WatchShortRooms.shareText(code, testRoomText)
        assertTrue(shared.contains("Codice stanza: $code"))
        assertTrue(shared.contains(link))
        assertFalse(shared.contains("NY1."))
        assertEquals(code, WatchShortRooms.normalizeInput(link))
        assertEquals(code, WatchShortRooms.normalizeInput(shared))

        val qr = WatchQr.encode(link)
        val pixels = IntArray(qr.width * qr.height) {
            if (qr[it % qr.width, it / qr.width]) 0xff000000.toInt() else 0xffffffff.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(qr.width, qr.height, pixels)))
        val decoded = QRCodeReader().decode(bitmap, mapOf(DecodeHintType.PURE_BARCODE to true)).text
        assertEquals(link, decoded)
        assertThrows(IllegalArgumentException::class.java) { WatchShortRooms.link("1234567") }
        assertThrows(IllegalArgumentException::class.java) {
            WatchInvite.codeFromLink("nyanime://watch/v1#012345678", 1_800_000_000_000L)
        }
    }

    @Test
    fun externalLinksMustMatchTheExactRouteAndDoNotAcceptCredentialsOrQueries() {
        val now = 1_800_000_000_000L
        val identity = WatchIdentity()
        val code = WatchInvite.create(identity.publicKey, now).encode()
        for (link in listOf(
            "https://watch/v1#" + code,
            "nyanime://other/v1#" + code,
            "nyanime://user@watch/v1#" + code,
            "nyanime://watch/v1?redirect=elsewhere#" + code,
            "nyanime://watch/v2#" + code,
        )) {
            assertThrows(IllegalArgumentException::class.java) { WatchInvite.codeFromLink(link, now) }
        }
        identity.clear()
    }
}

package nyanime.rooms.voice

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RoomVoiceSignalTest {
    private val offer = RoomVoiceSignal(
        "a".repeat(32),
        1,
        RoomVoiceSignalType.Offer,
        "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n",
        senderSession = "b".repeat(32),
        targetSession = "c".repeat(32),
    )

    @Test
    fun onlyAudioAndCurrentBoundedOperationsAreAccepted() {
        assertTrue(offer.valid())
        assertFalse(offer.copy(description = offer.description + "m=video 9 UDP/TLS/RTP/SAVPF 96\r\n").valid())
        assertFalse(offer.copy(description = "x".repeat(8001)).valid())
        assertFalse(offer.copy(senderSession = "").valid())
        assertFalse(offer.copy(revision = 0).valid())
        assertFalse(offer.copy(description = "v=0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n").valid())
    }

    @Test
    fun candidateBatchesAreBoundedAndCannotInjectSdpLines() {
        val candidate = RoomVoiceCandidate("0", 0, "candidate:1 1 UDP 123 192.0.2.1 12345 typ host")
        assertTrue(candidate.valid())
        assertFalse(candidate.copy(candidate = candidate.candidate + "\r\nm=video").valid())
        val message = offer.copy(
            type = RoomVoiceSignalType.Candidates,
            description = "",
            candidates = listOf(candidate),
        )
        assertTrue(message.valid())
        assertFalse(message.copy(candidates = List(9) { candidate }).valid())
        assertFalse(message.copy(description = offer.description).valid())
    }
}

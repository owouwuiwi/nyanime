package eu.kanade.tachiyomi.data.tv

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TvPlaybackFailureTest {
    @Test fun `native DNS and missing manifests are not repeatedly retried as slow buffering`() {
        assertEquals(
            TvPlaybackFailure.ADDRESS,
            TvPlaybackFailure.classify("Failed to resolve hostname: Name or service not known"),
        )
        assertEquals(TvPlaybackFailure.UNAVAILABLE, TvPlaybackFailure.classify("HTTP error 404 Not Found"))
        assertEquals(TvPlaybackFailure.UNAVAILABLE, TvPlaybackFailure.classify("HTTP error 410 Gone"))
        assertFalse(TvPlaybackFailure.ADDRESS.retryable)
    }

    @Test fun `access refusal is distinct from geographic availability and server overload`() {
        assertEquals(TvPlaybackFailure.ACCESS, TvPlaybackFailure.classify("HTTP error 403 Forbidden"))
        assertEquals(TvPlaybackFailure.CONNECTION, TvPlaybackFailure.classify("HTTP error 429 Too Many Requests"))
        assertEquals(TvPlaybackFailure.CONNECTION, TvPlaybackFailure.classify("HTTP error 503 Service Unavailable"))
        assertEquals(
            TvPlaybackFailure.CONNECTION,
            TvPlaybackFailure.classify("Failed to resolve hostname: Temporary failure in name resolution"),
        )
    }

    @Test fun `security and decoder faults are preserved without retaining native message data`() {
        assertEquals(TvPlaybackFailure.TLS, TvPlaybackFailure.classify("Certificate verify failed"))
        assertEquals(TvPlaybackFailure.UNSUPPORTED, TvPlaybackFailure.classify("Unrecognized file format"))
        assertNull(TvPlaybackFailure.classify("opening https://media.example/watch?token=private"))
        assertNull(TvPlaybackFailure.classify("x".repeat(16_385)))
        assertNull(TvPlaybackFailure.classify(null))
    }
}

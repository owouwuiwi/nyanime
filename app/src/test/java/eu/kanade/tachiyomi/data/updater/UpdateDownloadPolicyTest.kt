package eu.kanade.tachiyomi.data.updater

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import javax.net.ssl.SSLException

class UpdateDownloadPolicyTest {
    @Test fun aRestartedWorkerDoesNotRedownloadAnAlreadyInstalledOrNewerRelease() {
        val url = "https://github.com/owouwuiwi/nyanime/releases/download/v1.2.3.4/" +
            "Nyanime-Production-1.2.3.4-arm64-v8a.apk"
        assertTrue(UpdateDownloadPolicy.isAlreadyInstalled(url, "1.2.3.4"))
        assertTrue(UpdateDownloadPolicy.isAlreadyInstalled(url, "1.3.0.0"))
        assertFalse(UpdateDownloadPolicy.isAlreadyInstalled(url, "1.2.3.3"))
        assertFalse(UpdateDownloadPolicy.isAlreadyInstalled(url, "legacy"))
        assertFalse(UpdateDownloadPolicy.isAlreadyInstalled("https://example.invalid/update.apk", "1.2.3.4"))
    }

    @Test fun retriesOnlyTransientFailuresAndStopsAfterThreeAttempts() {
        assertTrue(UpdateDownloadPolicy.shouldRetry(UpdateHttpException(503), 0))
        assertTrue(UpdateDownloadPolicy.shouldRetry(UpdateHttpException(429), 1))
        assertTrue(UpdateDownloadPolicy.shouldRetry(IOException(), 1))
        assertFalse(UpdateDownloadPolicy.shouldRetry(IOException(), 2))
        assertFalse(UpdateDownloadPolicy.shouldRetry(UpdateHttpException(404), 0))
        assertFalse(UpdateDownloadPolicy.shouldRetry(SSLException("certificate"), 0))
        assertFalse(UpdateDownloadPolicy.shouldRetry(IllegalArgumentException("wrong package"), 0))
    }

    @Test fun unknownLengthsRemainIndeterminateAndProgressCannotOverflow() {
        assertNull(UpdateDownloadPolicy.progress(100, -1))
        assertNull(UpdateDownloadPolicy.progress(0, 0))
        assertEquals(50, UpdateDownloadPolicy.progress(Long.MAX_VALUE / 2, Long.MAX_VALUE))
        assertEquals(100, UpdateDownloadPolicy.progress(200, 100))
    }
}

package eu.kanade.tachiyomi.data.releases

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class ReleaseNotificationDeliveryTest {
    @Test
    fun `disabled notifications preserve pending notices until enabled`() = runTest {
        var enabled = false
        var posted = 0
        var acknowledged = false
        suspend fun deliver() = ReleaseNotificationDelivery.deliver(
            { enabled },
            { posted++ },
            { acknowledged = true },
        )
        deliver()
        assertEquals(0, posted)
        assertFalse(acknowledged)
        enabled = true
        deliver()
        assertEquals(1, posted)
        assertTrue(acknowledged)
    }

    @Test
    fun `permission revoked while building artwork does not discard notice`() = runTest {
        var enabled = true
        var acknowledged = false
        ReleaseNotificationDelivery.deliver(
            { enabled },
            { enabled = false },
            { acknowledged = true },
        )
        assertFalse(acknowledged)
    }

    @Test
    fun `posting failure or cancellation leaves receipt untouched`() = runTest {
        for (failure in listOf(IOException("Post failed"), CancellationException("App stopping"))) {
            var acknowledged = false
            try {
                ReleaseNotificationDelivery.deliver({ true }, { throw failure }, { acknowledged = true })
                error("Expected posting failure")
            } catch (error: Exception) {
                assertEquals(failure, error)
            }
            assertFalse(acknowledged)
        }
    }
}

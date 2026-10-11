package nyanime.tv.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TvRecoveryTest {
    @Test fun `retries are finite and cannot affect another channel or a local pause`() {
        val recovery = TvRecovery().apply { select(1) }
        assertNull(recovery.retry(2, 1000, false))
        assertNull(recovery.retry(1, 1000, true))
        assertEquals(
            listOf(1000L, 2000L, 4000L, 8000L, 12000L, 20000L),
            (1..6).map {
                recovery.retry(1, it * 1000L, false)
            },
        )
        assertNull(recovery.retry(1, 9000, false))
        recovery.select(2)
        assertEquals(1000L, recovery.retry(2, 10000, false))
    }

    @Test fun `a brief successful restart does not cause infinite fast retries`() {
        val recovery = TvRecovery().apply { select(1) }
        recovery.retry(1, 1000, false)
        recovery.ready(1, 2000)
        assertEquals(2000L, recovery.retry(1, 3000, false))
        recovery.ready(1, 4000)
        assertEquals(1000L, recovery.retry(1, 40000, false))
    }

    @Test fun `buffering after loading cannot reset the finite retry budget`() {
        val recovery = TvRecovery().apply { select(1) }
        assertEquals(1000L, recovery.retry(1, 1000, false))
        recovery.ready(1, 2000)
        recovery.interrupted(1)
        assertEquals(2000L, recovery.retry(1, 60000, false))
    }
}

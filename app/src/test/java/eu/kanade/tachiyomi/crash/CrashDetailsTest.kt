package eu.kanade.tachiyomi.crash

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CrashDetailsTest {
    @Test fun `serialized trace is shown without the recovery process stack`() {
        val original = "IllegalStateException: Example failure\n at example.Entry.open(Entry.kt:42)"
        assertEquals(original, crashDetails(Throwable(original))?.text)
    }

    @Test fun `an actual exception retains its type and stack`() {
        val details = crashDetails(IllegalStateException("Example failure"))!!
        assertTrue(details.text.startsWith("java.lang.IllegalStateException: Example failure"))
        assertTrue(details.text.contains("CrashDetailsTest"))
    }

    @Test fun `absent and empty details are not rendered as null`() {
        assertNull(crashDetails(null))
        assertNull(crashDetails(Throwable("  ")))
    }

    @Test fun `a long trace has a bounded preview without changing the original`() {
        val original = (1..200).joinToString("\n") { "Frame $it " + "x".repeat(200) }
        val exception = Throwable(original)
        val details = crashDetails(exception)!!
        assertTrue(details.truncated)
        assertTrue(details.text.length <= 8_000)
        assertTrue(details.text.lines().size <= 60)
        assertEquals(original, exception.message)
    }

    @Test fun `line limit also applies to short frames`() {
        val details = crashDetails(Throwable((1..100).joinToString("\n") { "Frame $it" }))!!
        assertEquals(60, details.text.lines().size)
        assertTrue(details.truncated)
    }
}

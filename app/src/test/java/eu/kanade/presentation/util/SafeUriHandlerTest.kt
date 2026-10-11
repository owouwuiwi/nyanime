package eu.kanade.presentation.util

import androidx.compose.ui.platform.UriHandler
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SafeUriHandlerTest {
    @Test
    fun `an unavailable or forbidden Android handler produces one user visible failure`() {
        listOf(IllegalArgumentException("No activity"), SecurityException("Not permitted"))
            .forEach { failure ->
                var unavailable = 0
                val handler = SafeUriHandler(delegate { throw failure }, { unavailable++ })
                handler.openUri("https://example.invalid/")
                assertEquals(1, unavailable)
            }
    }

    @Test
    fun `valid Android app links retain the existing handler`() {
        val opened = mutableListOf<String>()
        val handler = SafeUriHandler(delegate(opened::add), { error("Unexpected failure") })
        listOf(
            "https://example.invalid/",
            "mailto:test@example.invalid",
            "nyanime://open/v1#example",
        )
            .forEach(handler::openUri)
        assertEquals(3, opened.size)
        assertEquals("nyanime://open/v1#example", opened.last())
    }

    @Test
    fun `document links resolve first and invalid links never reach Android`() {
        val opened = mutableListOf<String>()
        var unavailable = 0
        val handler =
            SafeUriHandler(
                delegate(opened::add),
                { unavailable++ },
                "https://example.invalid/CHANGELOG.md",
            )
        handler.openUri("docs/guide.md")
        handler.openUri("javascript:alert(1)")
        handler.openUri("")
        assertEquals(listOf("https://example.invalid/docs/guide.md"), opened)
        assertEquals(2, unavailable)
    }

    @Test
    fun `cancellation and unexpected programming errors are not swallowed`() {
        val cancelled = CancellationException("Screen closed")
        val broken = NullPointerException("Unrelated failure")
        listOf(cancelled, broken).forEach { failure ->
            val handler =
                SafeUriHandler(delegate { throw failure }, { error("Unexpected failure callback") })
            assertSame(
                failure,
                assertThrows(failure.javaClass) { handler.openUri("https://example.invalid/") },
            )
        }
    }

    private fun delegate(open: (String) -> Unit) =
        object : UriHandler {
            override fun openUri(uri: String) = open(uri)
        }
}

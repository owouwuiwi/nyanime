package eu.kanade.presentation.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DocumentLinksTest {
    private val document = "https://example.invalid/project/blob/v1.2.3.4/CHANGELOG.md"

    @Test
    fun `relative documentation and anchors resolve against the original document`() {
        assertEquals(
            "https://example.invalid/project/blob/v1.2.3.4/docs/guide.md",
            resolveDocumentLink("docs/guide.md", document),
        )
        assertEquals("$document#details", resolveDocumentLink("#details", document))
        assertEquals(
            "https://example.invalid/project/blob/v1.2.3.4/guide.md",
            resolveDocumentLink("./docs/../guide.md", document),
        )
    }

    @Test
    fun `article relative and absolute links retain their intended destination`() {
        val article = "https://example.invalid/articles/entry"
        assertEquals(
            "https://example.invalid/articles/details",
            resolveDocumentLink("details", article),
        )
        assertEquals(
            "https://other.invalid/path?q=one#two",
            resolveDocumentLink("https://other.invalid/path?q=one#two", article),
        )
        assertEquals("https://example.invalid/help", resolveDocumentLink("/help", article))
        assertEquals(
            "https://other.invalid/help",
            resolveDocumentLink("//other.invalid/help", article),
        )
    }

    @Test
    fun `executable local credentialed and malformed links are not opened`() {
        listOf(
            "",
            " ",
            "javascript:alert(1)",
            "data:text/html,test",
            "file:///private/test",
            "content://private/test",
            "intent://example#Intent;end",
            "https://user:password@example.invalid/",
            "https://[invalid/",
            "https://example.invalid/\nprivate",
        )
            .forEach { assertNull(resolveDocumentLink(it, document), it) }
        assertNull(resolveDocumentLink("relative/path", "not a document URL"))
    }
}

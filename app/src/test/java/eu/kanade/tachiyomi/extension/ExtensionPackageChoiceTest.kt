package eu.kanade.tachiyomi.extension

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExtensionPackageChoiceTest {
    private val publisher = setOf("a".repeat(64))
    private fun candidate(key: String, version: Long, valid: Boolean = true) =
        ExtensionPackageChoice.Candidate(key, version, publisher, compatible = valid)

    @Test fun newestCompatibleVersionHasNoFormatPriority() {
        val choices = listOf(candidate("legacy", 3), candidate("system", 5), candidate("imported", 4))
        assertEquals("system", ExtensionPackageChoice.select(choices, "legacy", publisher)?.key)
        assertEquals(
            "legacy",
            ExtensionPackageChoice.select(
                choices.map {
                    it.copy(compatible = it.key == "legacy")
                },
                "legacy",
                publisher,
            )?.key,
        )
    }

    @Test fun tiesKeepActiveChoice() {
        val choices = listOf(candidate("system", 4), candidate("imported", 4))
        assertEquals("imported", ExtensionPackageChoice.select(choices, "imported", publisher)?.key)
    }

    @Test fun aDifferentPublisherCannotTakeOver() {
        val other = candidate("foreign", 100).copy(signers = setOf("b".repeat(64)), history = setOf("b".repeat(64)))
        assertEquals(
            "active",
            ExtensionPackageChoice.select(listOf(other, candidate("active", 1)), "active", publisher)?.key,
        )
        assertNull(ExtensionPackageChoice.select(listOf(other), "active", publisher))
    }

    @Test fun verifiedRotationKeepsContinuityButMultipleSignersRequireExactMatch() {
        val rotated = candidate("rotation", 2).copy(
            signers = setOf("b".repeat(64)),
            history =
            publisher + "b".repeat(64),
        )
        assertTrue(ExtensionPackageChoice.samePublisher(publisher, rotated))
        assertFalse(ExtensionPackageChoice.samePublisher(publisher + "c".repeat(64), rotated))
    }

    @Test fun absentApprovalDoesNotSelectExecutableCode() {
        assertNull(ExtensionPackageChoice.select(listOf(candidate("imported", 1)), null, emptySet()))
    }
}

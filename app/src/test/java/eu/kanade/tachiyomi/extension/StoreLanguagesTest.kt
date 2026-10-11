package eu.kanade.tachiyomi.extension

import eu.kanade.tachiyomi.data.extensionstore.StoreLanguages
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StoreLanguagesTest {
    @Test
    fun savedUnsupportedSelectionsAreRemovedWithoutDisablingSupportedOnes() {
        assertEquals(setOf("it", "en"), StoreLanguages.normalize(setOf("it", "en", "zz", "all")))
        assertEquals(setOf("en"), StoreLanguages.normalize(setOf("en", "zz")))
        assertEquals(setOf("it"), StoreLanguages.normalize(setOf("zz")))
    }

    @Test
    fun switchingToEnglishOnlyDoesNotLeaveAHiddenLanguageOrAnEmptyCatalogue() {
        val both = StoreLanguages.toggle(setOf("it", "zz"), "en")
        assertEquals(setOf("it", "en"), both)
        val english = StoreLanguages.toggle(both, "it")
        assertEquals(setOf("en"), english)
        assertEquals(english, StoreLanguages.toggle(english, "en"))
        assertEquals(english, StoreLanguages.toggle(english, "zz"))
    }
}

package it.lorelore.nyanime.reader.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class QualityTitleTest {
    @Test
    fun `identity survives a different database row without merging other sources or works`() {
        val original = QualityTitle(17, "/works/illustrated-story")
        assertEquals(original.storageKey, QualityTitle(17, "/works/illustrated-story").storageKey)
        assertNotEquals(original.storageKey, QualityTitle(18, "/works/illustrated-story").storageKey)
        assertNotEquals(original.storageKey, QualityTitle(17, "/works/illustrated-story-2").storageKey)
        assertEquals(64, original.storageKey.length)
    }
}

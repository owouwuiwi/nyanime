package eu.kanade.tachiyomi.data.updater

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReadyAppUpdateTest {
    @Test
    fun `numeric versions migrate from legacy and never downgrade between channels`() {
        assertTrue(isNewerAppUpdate(133, "0.18.1.4-9000", 134, "0.19.0.0"))
        assertTrue(isNewerAppUpdate(134, "0.19.0.0", 135, "0.19.0.1"))
        assertFalse(isNewerAppUpdate(135, "0.19.0.1", 134, "0.19.0.0"))
        assertFalse(isNewerAppUpdate(134, "0.19.0.0", 134, "0.19.0.0"))
        assertTrue(isNewerAppUpdate(134, "0.19.0.9", 134, "0.19.0.10"))
        assertFalse(isNewerAppUpdate(134, "0.19.1.0", 135, "0.19.0.9"))
    }

    @Test
    fun `preview update is newer even when version codes are equal`() {
        assertTrue(isNewerAppUpdate(133, "0.18.1.4-8270", 133, "0.18.1.4-8273"))
        assertFalse(isNewerAppUpdate(133, "0.18.1.4-8273", 133, "0.18.1.4-8273"))
        assertFalse(isNewerAppUpdate(133, "0.18.1.4-8273", 133, "0.18.1.4-8270"))
        assertFalse(isNewerAppUpdate(133, "0.18.1.4-8270", 133, "other-8273"))
        assertTrue(isNewerAppUpdate(132, "0.18.1.4-8270", 133, "0.18.1.4-8273"))
        assertFalse(isNewerAppUpdate(133, "0.18.1.4-8270", 132, "0.18.1.4-8273"))
    }
}

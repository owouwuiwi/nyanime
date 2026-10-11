package eu.kanade.tachiyomi.ui.updates

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference

class UpdateNoticeTest {
    @Test
    fun `opening updates clears the notice without hiding titles and later updates restore it`() {
        val preference = mockk<Preference<Long>>()
        var lastSeenAt = 0L
        every { preference.get() } answers { lastSeenAt }
        every { preference.set(any()) } answers { lastSeenAt = firstArg() }
        val existing = "${System.currentTimeMillis() - 60_000}|anime|1|Title|Episode 1"

        assertTrue(hasNewLibraryUpdateNotice(listOf(existing), lastSeenAt))
        markLibraryUpdateNoticesSeen(preference, listOf(existing))

        assertFalse(hasNewLibraryUpdateNotice(listOf(existing), lastSeenAt))
        assertFalse(
            hasNewLibraryUpdateNotice(listOf(existing, "${lastSeenAt - 1}|anime|1|Title|Episode 0"), lastSeenAt),
        )
        assertTrue(hasNewLibraryUpdateNotice(listOf(existing, "${lastSeenAt + 1}|anime|1|Title|Episode 2"), lastSeenAt))
    }
}

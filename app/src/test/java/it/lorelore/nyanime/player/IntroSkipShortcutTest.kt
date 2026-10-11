package it.lorelore.nyanime.player

import eu.kanade.tachiyomi.animesource.model.ChapterType
import eu.kanade.tachiyomi.ui.player.controls.components.IndexedSegment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.custombuttons.model.CustomButton

class IntroSkipShortcutTest {
    private val button = CustomButton(
        id = 1L,
        name = "+85 s",
        isFavorite = true,
        sortIndex = 0,
        content = """
            local intro_length = mp.get_property_number("user-data/current-anime/intro-length")
            aniyomi.right_seek_by(intro_length)
        """.trimIndent(),
        longPressContent = "",
        onStartup = "",
    )
    private val chapters = listOf(
        IndexedSegment("", 0f),
        IndexedSegment("Opening", 60f, chapterType = ChapterType.Opening),
        IndexedSegment("", 145f),
    )

    @Test fun `shortcut is available before and during an identified opening but not after it`() {
        assertEquals(button, visible(0f))
        assertEquals(button, visible(80f))
        assertEquals(button, visible(144.9f))
        assertNull(visible(145f))
        assertNull(visible(1000f))
    }

    @Test fun `seeking back into an identified opening makes the shortcut useful again`() {
        assertNull(visible(150f))
        assertEquals(button, visible(80f))
    }

    @Test fun `manual use hides immediately even before any position acknowledgement`() {
        val skipped = IntroSkipShortcut.skipped(42, 90f, 85, 1440f)!!
        assertNull(visible(90f, emptyList(), skipped))
        assertFalse(skipped.reached)
    }

    @Test fun `stale position callbacks cannot restore a shortcut before the skip completes`() {
        var skipped = IntroSkipShortcut.skipped(42, 90f, 85, 1440f)!!
        listOf(89f, 90f, 91f).forEach { position ->
            skipped = skipped.observe(42, position)!!
            assertNull(visible(position, emptyList(), skipped))
        }
    }

    @Test fun `returning to the skipped position restores the shortcut only after progress is confirmed`() {
        val skipped = IntroSkipShortcut.skipped(42, 90f, 85, 1440f)!!.observe(42, 175f)!!
        assertTrue(skipped.reached)
        assertNotNull(skipped.observe(42, 176f))
        assertNull(skipped.observe(42, 90f))
        assertEquals(button, visible(90f, emptyList(), skipped.observe(42, 90f)))
    }

    @Test fun `quality changes preserve the same episode dismissal and a new episode does not inherit it`() {
        val skipped = IntroSkipShortcut.skipped(42, 90f, 85, 1440f)!!.observe(42, 175f)!!
        val preparing = skipped.preparing(42)!!.observe(42, 0f)
        assertNull(visible(0f, emptyList(), preparing))
        assertNull(skipped.preparing(43))
        assertNull(skipped.observe(43, 0f))
        assertEquals(button, IntroSkipShortcut.visible(button, 43, 0f, emptyList(), skipped))
    }

    @Test fun `customized actions are not hidden merely because their title or id resembles the default`() {
        val custom = button.copy(content = "aniyomi.seek_by(85)")
        val skipped = IntroSkipShortcut.skipped(42, 90f, 85, 1440f)
        assertFalse(IntroSkipShortcut.isDefault(custom))
        assertEquals(custom, IntroSkipShortcut.visible(custom, 42, 200f, chapters, skipped))
        assertFalse(IntroSkipShortcut.isDefault(button.copy(id = 9)))
    }

    @Test fun `renaming or changing line endings does not break the built in role`() {
        assertTrue(
            IntroSkipShortcut.isDefault(button.copy(name = "Salta", content = button.content.replace("\n", "\r\n"))),
        )
    }

    @Test fun `missing opening endpoints and ending only metadata do not invent a skip boundary`() {
        assertEquals(button, visible(500f, listOf(chapters[1])))
        assertEquals(
            button,
            visible(500f, listOf(IndexedSegment("Ending", 60f, chapterType = ChapterType.Ending), chapters[2])),
        )
    }

    @Test fun `a later opening has its own interval`() {
        val segments = chapters +
            listOf(
                IndexedSegment("Opening 2", 800f, chapterType = ChapterType.MixedOp),
                IndexedSegment("", 890f),
            )
        assertNull(visible(600f, segments))
        assertEquals(button, visible(850f, segments))
        assertNull(visible(891f, segments))
    }

    @Test fun `invalid requests do not consume a shortcut and targets stop at the duration`() {
        assertNull(IntroSkipShortcut.skipped(null, 0f, 85, 100f))
        assertNull(IntroSkipShortcut.skipped(42, Float.NaN, 85, 100f))
        assertNull(IntroSkipShortcut.skipped(42, 0f, 0, 100f))
        assertNull(IntroSkipShortcut.skipped(42, 100f, 85, 100f))
        assertEquals(100f, IntroSkipShortcut.skipped(42, 90f, 85, 100f)?.target)
    }

    private fun visible(
        position: Float,
        segments: List<IndexedSegment> = chapters,
        skipped: IntroSkipShortcut.Skipped? = null,
    ) = IntroSkipShortcut.visible(button, 42, position, segments, skipped)
}

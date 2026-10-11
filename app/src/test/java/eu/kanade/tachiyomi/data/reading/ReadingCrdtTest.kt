package eu.kanade.tachiyomi.data.reading

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class ReadingCrdtTest {
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val page = ReadingPosition(7, "/title", "/chapter", "Title", "Chapter", 1, 20)

    private fun id(number: Int) = number.toString(16).padStart(32, '0')

    private fun stroke(number: Int, author: String, clock: Long) = ReadingEdit(
        id(number),
        author,
        page,
        clock,
        ReadingEditKind.Stroke,
        stroke = ReadingStroke(id(number), author, 0, 2, listOf(0, 0, 10000, 10000)),
    )

    @Test fun reorderedAndDuplicatedEditsConvergeWithoutResurrectingHiddenStroke() {
        val create = stroke(1, alice, 1)
        val second = stroke(2, bob, 1)
        val hide = ReadingEdit(id(3), alice, page, 3, ReadingEditKind.Visibility, target = id(1), visible = false)
        val restore = ReadingEdit(id(4), alice, page, 4, ReadingEditKind.Visibility, target = id(1))
        val hideAgain = ReadingEdit(id(5), alice, page, 5, ReadingEditKind.Visibility, target = id(1), visible = false)
        val expected = listOf(id(2))
        val edits = listOf(create, second, hide, restore, hideAgain)
        repeat(40) { seed ->
            val document = ReadingCrdt()
            val shuffled = edits.shuffled(Random(seed))
            shuffled.forEach { assertTrue(document.apply(it)) }
            shuffled.forEach { assertFalse(document.apply(it)) }
            assertEquals(expected, document.strokes(page).map { it.id })
            assertEquals(5, document.all().size)
        }
    }

    @Test fun noteEditsAndVisibilityCanArriveBeforeTheNote() {
        val create = ReadingEdit(
            id(10),
            alice,
            page,
            1,
            ReadingEditKind.Note,
            note = ReadingNote(id(10), alice, 2000, 3000, "First"),
        )
        val edit = ReadingEdit(id(11), alice, page, 2, ReadingEditKind.NoteText, target = id(10), text = "Revised")
        val hide = ReadingEdit(id(12), alice, page, 3, ReadingEditKind.Visibility, target = id(10), visible = false)
        val show = ReadingEdit(id(13), alice, page, 4, ReadingEditKind.Visibility, target = id(10))
        val document = ReadingCrdt()
        listOf(show, hide, edit, create).forEach { assertTrue(document.apply(it)) }
        assertEquals("Revised", document.notes(page).single().text)
        assertEquals(4, document.history(alice).size)
        assertEquals(ReadingEditKind.Visibility, document.history(alice).first().kind)
    }

    @Test fun digestIsIndependentOfArrivalOrder() {
        val edits = listOf(stroke(1, alice, 1), stroke(2, bob, 1), stroke(3, alice, 2))
        val first = ReadingCrdt()
        val second = ReadingCrdt()
        edits.forEach(first::apply)
        edits.reversed().forEach(second::apply)
        assertEquals(first.digest(page), second.digest(page))
    }

    @Test fun onlyAuthorOrHostCanHideSharedDrawing() {
        val document = ReadingCrdt(hostId = "c".repeat(64))
        assertTrue(document.apply(stroke(1, bob, 1)))
        assertTrue(
            document.apply(
                ReadingEdit(id(2), alice, page, 2, ReadingEditKind.Visibility, target = id(1), visible = false),
            ),
        )
        assertEquals(listOf(id(1)), document.strokes(page).map { it.id })
        assertTrue(
            document.apply(
                ReadingEdit(
                    id(3),
                    "c".repeat(64),
                    page,
                    3,
                    ReadingEditKind.Visibility,
                    target = id(1),
                    visible = false,
                ),
            ),
        )
        assertTrue(document.strokes(page).isEmpty())
    }
}

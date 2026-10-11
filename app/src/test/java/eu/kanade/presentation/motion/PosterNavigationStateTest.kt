package eu.kanade.presentation.motion

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class PosterNavigationStateTest {
    @Test
    fun `return shading and backdrop belong to their own route and are released on pop`() {
        val state = PosterNavigationState<String>()
        val backdrop = HazeState()
        val shade = Brush.verticalGradient(listOf(Color.Transparent, Color.Black))
        state.connect("home", "first", "card", "Title", "image", backdrop = backdrop)
        state.destination("first")!!.shade = shade
        state.connect("first", "second", "related", "Other title", "other image")

        assertSame(shade, state.between("first", "home")?.shade)
        assertSame(backdrop, state.between("first", "home")?.backdrop)
        assertNull(state.between("second", "first")?.shade)
        assertNull(state.between("second", "first")?.backdrop)

        state.retain(setOf("home", "first"))
        assertSame(shade, state.destination("first")?.shade)
        state.retain(setOf("home"))
        assertNull(state.destination("first"))
    }

    @Test
    fun `duplicate titles use only the tapped card and the same pair on return`() {
        val state = PosterNavigationState<String>()
        val shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        state.connect("home", "details", "second-row-card", "Same title", "decoded-poster", shape)
        assertEquals("second-row-card", state.between("home", "details")?.element)
        assertSame(shape, state.between("details", "home")?.shape)
        assertEquals(state.between("home", "details"), state.between("details", "home"))
        assertNull(state.between("other-home", "details"))
        assertNull(state.between("details", "details"))
    }

    @Test
    fun `nested detail navigation retains separate return destinations`() {
        val state = PosterNavigationState<String>()
        state.connect("home", "first", "home-card", "First", "one")
        state.connect("first", "second", "related-card", "Second", "two")
        assertEquals("related-card", state.between("second", "first")?.element)
        assertEquals("home-card", state.between("first", "home")?.element)
        assertNull(state.between("home", "second"))
    }

    @Test
    fun `popped artwork is released after transition while previous route remains available`() {
        val state = PosterNavigationState<String>()
        state.connect("home", "first", "home-card", "First", "one")
        state.connect("first", "second", "related-card", "Second", "two")
        state.retain(setOf("home", "first", "second"))
        assertEquals("two", state.destination("second")?.artwork)
        state.retain(setOf("home", "first"))
        assertNull(state.destination("second"))
        assertEquals("one", state.destination("first")?.artwork)
        state.retain(setOf("home"))
        assertNull(state.destination("first"))
    }

    @Test
    fun `removed origin and disabling motion cannot leave an orphan return image`() {
        val state = PosterNavigationState<String>()
        state.connect("home", "details", "card", "Title", "image")
        state.retain(setOf("details"))
        assertNull(state.destination("details"))
        state.connect("home", "details", "card", "Title", "image")
        state.clear()
        assertNull(state.between("details", "home"))
    }

    @Test
    fun `a click without a new destination never records a transition`() {
        val state = PosterNavigationState<String>()
        state.connect("home", "home", "card", "Title", "image")
        assertNull(state.destination("home"))
    }
}

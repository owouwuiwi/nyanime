package eu.kanade.presentation.motion

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PosterOverlayShapeTest {
    @Test
    fun `source and destination retain their own corners during both navigation directions`() {
        val fraction = mutableStateOf(1f)
        val shape = PosterOverlayShape(RoundedCornerShape(8.dp), RoundedCornerShape(20.dp), fraction)
        assertEquals(8f, radius(shape))
        fraction.value = 0.5f
        assertEquals(14f, radius(shape))
        fraction.value = 0f
        assertEquals(20f, radius(shape))
        fraction.value = 1f
        assertEquals(8f, radius(shape))
    }

    @Test
    fun `a rectangular source can reach rounded details without a final corner jump`() {
        val fraction = mutableStateOf(1f)
        val shape = PosterOverlayShape(RectangleShape, RoundedCornerShape(20.dp), fraction)
        assertEquals(0f, radius(shape))
        fraction.value = 0.5f
        assertEquals(10f, radius(shape))
        fraction.value = 0f
        assertEquals(20f, radius(shape))
    }

    private fun radius(shape: PosterOverlayShape): Float =
        (shape.createOutline(Size(300f, 320f), LayoutDirection.Ltr, Density(1f)) as Outline.Rounded)
            .roundRect.topLeftCornerRadius.x
}

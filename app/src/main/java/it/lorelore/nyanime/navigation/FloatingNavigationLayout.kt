package it.lorelore.nyanime.navigation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Navigation, search and the first layout pass reserve the same compact footer. */
internal object FloatingNavigationLayout {
    val contentPadding = 4.dp
    val verticalMargin = 8.dp
    val verticalInset = contentPadding + contentPadding + verticalMargin + verticalMargin

    fun itemMinHeight(compact: Boolean): Dp = if (compact) 56.dp else 60.dp
}

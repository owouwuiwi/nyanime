package it.lorelore.nyanime.navigation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Navigation, search and the first layout pass reserve the same compact footer. */
internal object FloatingNavigationLayout {
    val contentPadding = 4.dp
    val topMargin = 8.dp
    val bottomMargin = 0.dp
    val verticalInset = contentPadding + contentPadding + topMargin + bottomMargin

    fun itemMinHeight(compact: Boolean): Dp = if (compact) 56.dp else 60.dp
}

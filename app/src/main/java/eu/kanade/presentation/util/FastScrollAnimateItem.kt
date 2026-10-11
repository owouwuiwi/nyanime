// ktlint 1.7 rewrites named context parameters into a form rejected by the same rule.
@file:Suppress("ktlint:standard:context-receiver-list-wrapping")

package eu.kanade.presentation.util

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.ui.Modifier

// https://issuetracker.google.com/352584409
context(itemScope: LazyItemScope) fun Modifier.animateItemFastScroll() = with(itemScope) {
    this@animateItemFastScroll.animateItem(fadeInSpec = null, fadeOutSpec = null)
}

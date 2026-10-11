// SPDX-License-Identifier: Apache-2.0
package it.lorelore.nyanime.presentation.player

import androidx.compose.runtime.staticCompositionLocalOf

/** Only the MPV/live host supplies its own feedback; the embedded video's default stays unchanged. */
internal val LocalHostSeekFeedback = staticCompositionLocalOf { false }

internal val LocalHostSeekCancelled = staticCompositionLocalOf<(() -> Unit)?> { null }

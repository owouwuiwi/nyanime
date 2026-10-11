// Copyright (c) 2026 owouwuiwi and Nyanime contributors.
// SPDX-License-Identifier: Apache-2.0
// Original Nyanime implementation; see this module's LICENSE.

package it.lorelore.nyanime.presentation.player

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable

@Composable
internal fun PlayerMotion(reduceMotion: Boolean, content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = MaterialTheme.colorScheme,
        typography = MaterialTheme.typography,
        motionScheme = if (reduceMotion) NoPlayerMotion else MotionScheme.expressive(),
        content = content,
    )
}

private object NoPlayerMotion : MotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = snap()
}

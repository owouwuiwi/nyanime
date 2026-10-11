// SPDX-License-Identifier: Apache-2.0
package it.lorelore.nyanime.presentation.player

import android.view.MotionEvent
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.motionEventSpy

/** Attach to the actual Slider, never to a fullscreen sibling above video gestures. */
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.hostSeekReleaseGate(gate: HostSeekReleaseGate?): Modifier =
    if (gate == null) {
        this
    } else {
        motionEventSpy { event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> gate.pointerDown()
                MotionEvent.ACTION_UP -> gate.pointerUp()
            }
        }
    }

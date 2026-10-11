// SPDX-License-Identifier: Apache-2.0
package it.lorelore.nyanime.presentation.player

/** Touch completion needs a real release; keyboard/accessibility have no pointer sequence. */
class HostSeekReleaseGate {
    private var pointerSequence = false
    private var pointerReleased = false

    fun pointerDown() {
        pointerSequence = true
        pointerReleased = false
    }

    fun pointerUp() {
        if (pointerSequence) pointerReleased = true
    }

    fun finish(): Boolean {
        val released = !pointerSequence || pointerReleased
        pointerSequence = false
        pointerReleased = false
        return released
    }
}

// Copyright (c) 2026 owouwuiwi and Nyanime contributors.
// SPDX-License-Identifier: Apache-2.0
// Original Nyanime implementation; see this module's LICENSE.

package it.lorelore.nyanime.presentation.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TimelinePreviewTest {
    private val events = mutableListOf<String>()
    private val actions = VideoChromeActions(
        {},
        {},
        {},
        {},
        seek = { events += "seek:$it" },
        preview = { events += "preview:$it" },
        scrubbing = { events += "drag:$it" },
    )

    @Test
    fun `multiple previews commit exactly once`() {
        val drag = TimelinePreview()
        drag.update(.2f, 10_000L, actions)
        drag.update(.5f, 10_000L, actions)
        drag.finish(actions)
        drag.finish(actions)
        drag.cancel(actions)
        assertEquals(listOf("drag:true", "preview:2000", "preview:5000", "seek:5000", "drag:false"), events)
    }

    @Test
    fun `cancel or disposal never seeks`() {
        val drag = TimelinePreview()
        drag.update(.5f, 10_000L, actions)
        drag.cancel(actions)
        drag.finish(actions)
        assertEquals(listOf("drag:true", "preview:5000", "drag:false"), events)
    }

    @Test
    fun `touch finished without release cancels instead of committing`() {
        val drag = TimelinePreview()
        drag.beginPointer()
        drag.update(.5f, 10_000L, actions)
        drag.finish(actions)
        assertEquals(listOf("drag:true", "preview:5000", "drag:false"), events)
    }

    @Test
    fun `released touch commits once and next touch needs its own release`() {
        val drag = TimelinePreview()
        drag.beginPointer()
        drag.update(.5f, 10_000L, actions)
        drag.releasePointer()
        drag.finish(actions)
        drag.beginPointer()
        drag.update(.8f, 10_000L, actions)
        drag.finish(actions)
        assertEquals(
            listOf("drag:true", "preview:5000", "seek:5000", "drag:false", "drag:true", "preview:8000", "drag:false"),
            events,
        )
    }

    @Test
    fun `cancel before a preview still permits the next accessibility action`() {
        val drag = TimelinePreview()
        drag.beginPointer()
        drag.cancel(actions)
        drag.update(.5f, 10_000L, actions)
        drag.finish(actions)
        assertEquals(listOf("drag:true", "preview:5000", "seek:5000", "drag:false"), events)
    }

    @Test
    fun `unknown duration and nonfinite input do not pause playback`() {
        val drag = TimelinePreview()
        drag.update(.5f, 0L, actions)
        drag.update(Float.NaN, 10_000L, actions)
        drag.update(Float.POSITIVE_INFINITY, 10_000L, actions)
        drag.finish(actions)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `positions clamp and long videos do not overflow`() {
        val drag = TimelinePreview()
        drag.update(2f, Long.MAX_VALUE, actions)
        assertEquals(Long.MAX_VALUE, drag.targetMs)
        assertEquals(1f, playbackFraction(Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(0f, playbackFraction(500, 0))
    }

    @Test
    fun `hours and minutes are unambiguous`() {
        assertEquals("00:00", playbackTime(-1))
        assertEquals("04:03", playbackTime(243_000))
        assertEquals("1:01:01", playbackTime(3_661_000))
    }
}

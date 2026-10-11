package eu.kanade.tachiyomi.ui.player.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlaybackSurfaceLeaseTest {
    private val events = mutableListOf<String>()
    private val gate = PlaybackSurfaceLease<String>(
        attach = { events += "attach:$it" },
        detach = { events += "detach" },
        resize = { width, height -> events += "resize:${width}x$height" },
    )

    @Test fun `surface available before native initialization is attached only after the lease is ready`() {
        val host = Any()
        gate.activate(host)
        gate.offer(host, "one", 640, 360)
        assertEquals(emptyList<String>(), events)
        gate.initialized()
        gate.initialized()
        assertEquals(listOf("attach:one", "resize:640x360"), events)
    }

    @Test fun `late destruction and resize of the previous host cannot detach the current surface`() {
        val first = Any()
        val second = Any()
        gate.initialized()
        gate.offer(first, "one", 640, 360)
        gate.activate(first)
        gate.offer(second, "two", 320, 180)
        gate.activate(second)
        gate.removed(first)
        gate.offer(first, "old", 1920, 1080)
        gate.deactivate(first)
        assertEquals(listOf("attach:one", "resize:640x360", "detach", "attach:two", "resize:320x180"), events)
    }

    @Test fun `the incoming host does not clear the current frame until its replacement is ready`() {
        val first = Any()
        val second = Any()
        gate.initialized()
        gate.offer(first, "one", 640, 360)
        gate.activate(first)
        gate.activate(second)
        assertEquals(listOf("attach:one", "resize:640x360"), events)
        gate.offer(second, "two", 320, 180)
        assertEquals(listOf("attach:one", "resize:640x360", "detach", "attach:two", "resize:320x180"), events)
    }

    @Test fun `a destroyed surface is detached even while the next host is still preparing`() {
        val first = Any()
        val second = Any()
        gate.initialized()
        gate.offer(first, "one", 640, 360)
        gate.activate(first)
        gate.activate(second)
        gate.removed(first)
        assertEquals(listOf("attach:one", "resize:640x360", "detach"), events)
        gate.offer(second, "two", 320, 180)
        assertEquals("attach:two", events[3])
    }

    @Test fun `identical frames and callbacks after release make no native calls`() {
        val host = Any()
        gate.initialized()
        gate.offer(host, "one", 640, 360)
        gate.activate(host)
        gate.offer(host, "one", 640, 360)
        gate.activate(host)
        gate.close()
        gate.removed(host)
        gate.offer(host, "late", 10, 10)
        gate.activate(host)
        gate.initialized()
        gate.close()
        assertEquals(listOf("attach:one", "resize:640x360", "detach"), events)
    }
}

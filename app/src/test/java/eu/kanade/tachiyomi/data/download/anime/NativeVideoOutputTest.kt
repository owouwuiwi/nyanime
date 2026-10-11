package eu.kanade.tachiyomi.data.download.anime

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NativeVideoOutputTest {
    @Test fun descriptorIsAppliedOnlyToOutputEvenWhenAnInputUsesTheSameProtocol() {
        val arguments = arrayOf("-fd", "12", "-i", "fd:", "-c", "copy", "-f", "matroska", "fd:", "-y")
        assertArrayEquals(
            arrayOf("-fd", "12", "-i", "fd:", "-c", "copy", "-f", "matroska", "-fd", "34", "fd:", "-y"),
            nativeOutputArguments(arguments, "fd:", 34),
        )
    }

    @Test fun normalFilesKeepOriginalOptionsAndInvalidRoutingFailsBeforeStartingNativeWork() {
        val arguments = arrayOf("-i", "input.m3u8", "-c", "copy", "output.mkv")
        assertSame(arguments, nativeOutputArguments(arguments, "output.mkv", null))
        assertThrows(IllegalArgumentException::class.java) { nativeOutputArguments(arguments, "fd:", 34) }
        assertThrows(IllegalArgumentException::class.java) { nativeOutputArguments(arguments, "output.mkv", -1) }
    }
}

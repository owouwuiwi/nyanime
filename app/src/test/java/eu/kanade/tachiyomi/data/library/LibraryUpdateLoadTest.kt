package eu.kanade.tachiyomi.data.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LibraryUpdateLoadTest {
    @Test
    fun thresholdCountsChecksPerSourceRatherThanEntireLibrary() {
        assertNull(LibraryUpdateLoad.warning(List(60) { 1L } + List(60) { 2L }) { false })
        assertEquals(LibraryUpdateLoad(2, 61), LibraryUpdateLoad.warning(List(60) { 1L } + List(61) { 2L }) { false })
    }

    @Test
    fun unmeteredSourcesAndEmptyQueuesDoNotWarn() {
        assertNull(LibraryUpdateLoad.warning(emptyList()) { false })
        assertNull(LibraryUpdateLoad.warning(List(100) { 1L }) { true })
        assertEquals(
            LibraryUpdateLoad(2, 70),
            LibraryUpdateLoad.warning(List(100) { 1L } + List(70) { 2L }) { it == 1L },
        )
    }
}

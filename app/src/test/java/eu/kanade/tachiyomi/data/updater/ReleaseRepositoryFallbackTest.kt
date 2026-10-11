package eu.kanade.tachiyomi.data.updater

import eu.kanade.tachiyomi.network.HttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tachiyomi.data.release.ReleaseRepositoryFallback
import tachiyomi.domain.release.model.Release
import java.io.IOException

class ReleaseRepositoryFallbackTest {
    private val primary = "new-publisher/app"
    private val previous = "previous-publisher/app"
    private val release = Release("0.19.0.1", "notes", "https://example.test/release", "https://example.test/app.apk")

    @Test
    fun `new publisher wins without consulting the old address`() = runTest {
        val visited = mutableListOf<String>()
        assertSame(
            release,
            ReleaseRepositoryFallback.latest(primary, listOf(previous)) {
                visited.add(it)
                release
            },
        )
        assertEquals(listOf(primary), visited)
    }

    @Test
    fun `missing or unavailable publisher uses the previous address`() = runTest {
        for (error in listOf(HttpException(404), HttpException(503), IOException("disconnected"))) {
            val visited = mutableListOf<String>()
            assertSame(
                release,
                ReleaseRepositoryFallback.latest(primary, listOf(primary, previous)) {
                    visited.add(it)
                    if (it == primary) throw error
                    release
                },
            )
            assertEquals(listOf(primary, previous), visited)
        }
    }

    @Test
    fun `valid empty channel cannot be overridden by an old publication`() = runTest {
        val visited = mutableListOf<String>()
        assertNull(
            ReleaseRepositoryFallback.latest(primary, listOf(previous)) {
                visited.add(it)
                null
            },
        )
        assertEquals(listOf(primary), visited)
    }

    @Test
    fun `rate limits authentication cancellation and parsing errors stop immediately`() {
        for (error in listOf(
            HttpException(401),
            HttpException(403),
            HttpException(429),
            CancellationException("cancelled"),
            IllegalArgumentException("invalid response"),
        )) {
            val visited = mutableListOf<String>()
            val caught = assertThrows(error.javaClass) {
                runTest {
                    ReleaseRepositoryFallback.latest(primary, listOf(previous)) {
                        visited.add(it)
                        throw error
                    }
                }
            }
            assertSame(error, caught)
            assertEquals(listOf(primary), visited)
        }
    }

    @Test
    fun `all unavailable preserves the failure instead of reporting no updates`() {
        val visited = mutableListOf<String>()
        assertThrows(HttpException::class.java) {
            runTest {
                ReleaseRepositoryFallback.latest(primary, listOf(previous, previous)) {
                    visited.add(it)
                    throw HttpException(503)
                }
            }
        }
        assertEquals(listOf(primary, previous), visited)
    }
}

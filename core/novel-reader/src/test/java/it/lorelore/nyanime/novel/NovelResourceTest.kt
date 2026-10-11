package it.lorelore.nyanime.novel

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.coroutines.coroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class NovelResourceTest {
    @Test
    fun `cancelled dispatcher handoff releases a newly opened publication exactly once`() =
        runTest {
            var releases = 0
            val worker = StandardTestDispatcher(testScheduler)
            val reader = launch {
                acquireNovelResource(
                    worker,
                    acquire = {
                        coroutineContext[Job]!!.cancel()
                        Any()
                    },
                    release = { releases++ },
                )
            }
            reader.join()
            assertEquals(1, releases)
        }

    @Test
    fun `successful handoff transfers ownership to caller`() = runTest {
        var releases = 0
        val value = Any()
        val opened =
            acquireNovelResource(StandardTestDispatcher(testScheduler), { value }, { releases++ })
        assertEquals(value, opened)
        assertEquals(0, releases)
    }
}

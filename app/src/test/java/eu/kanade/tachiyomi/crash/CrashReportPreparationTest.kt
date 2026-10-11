package eu.kanade.tachiyomi.crash

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException

class CrashReportPreparationTest {
    @Test fun `repeated taps prepare once and ready state survives until consumed`() = runTest {
        val wait = CompletableDeferred<File>()
        var exports = 0
        val report = CrashReportPreparation(this) {
            exports++
            wait.await()
        }
        report.prepare(null)
        report.prepare(null)
        testScheduler.runCurrent()
        assertEquals(1, exports)
        assertEquals(CrashReportState.Preparing, report.state.value)
        val file = File("report.txt")
        wait.complete(file)
        testScheduler.runCurrent()
        report.prepare(null)
        assertEquals(1, exports)
        assertEquals(CrashReportState.Ready(file), report.state.value)
        report.consumed(File("other.txt"))
        assertEquals(CrashReportState.Ready(file), report.state.value)
        report.consumed(file)
        assertEquals(CrashReportState.Idle, report.state.value)
    }

    @Test fun `export failures can be retried`() = runTest {
        var exports = 0
        val file = File("report.txt")
        val report = CrashReportPreparation(this) {
            if (++exports == 1) throw IOException("Unavailable")
            file
        }
        report.prepare(null)
        testScheduler.runCurrent()
        assertEquals(CrashReportState.Failed, report.state.value)
        report.prepare(null)
        testScheduler.runCurrent()
        assertEquals(CrashReportState.Ready(file), report.state.value)
    }

    @Test fun `closing during export does not publish a ready report`() = runTest {
        val job = Job()
        val wait = CompletableDeferred<File>()
        val report = CrashReportPreparation(CoroutineScope(coroutineContext + job)) { wait.await() }
        report.prepare(null)
        testScheduler.runCurrent()
        job.cancel()
        wait.complete(File("report.txt"))
        testScheduler.runCurrent()
        assertEquals(CrashReportState.Idle, report.state.value)
    }

    @Test fun `a failed share only changes the matching ready report and permits retry`() = runTest {
        val file = File("report.txt")
        val report = CrashReportPreparation(this) { file }
        report.prepare(null)
        testScheduler.runCurrent()
        report.shareFailed(File("other.txt"))
        assertEquals(CrashReportState.Ready(file), report.state.value)
        report.shareFailed(file)
        assertEquals(CrashReportState.Failed, report.state.value)
        report.prepare(null)
        testScheduler.runCurrent()
        assertEquals(CrashReportState.Ready(file), report.state.value)
    }
}

package eu.kanade.tachiyomi.data.updater

import androidx.work.Data
import androidx.work.WorkInfo
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

class AppUpdateRepositoryTest {
    @Test
    fun `progress emissions do not inspect APKs again and foreground return detects a deleted file`() = runTest {
        val id = UUID.randomUUID()
        val finished = work(WorkInfo.State.SUCCEEDED, id)
        val works = MutableStateFlow(listOf(finished))
        val refresh = MutableStateFlow(0)
        var inspections = 0
        var available = true
        var result: UpdateValidationBatch? = null
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            validatedAppUpdates(works, refresh) {
                inspections++
                ReadyAppUpdate(it.id.toString(), File("update.apk")).takeIf { available }
            }.collect { result = it }
        }
        runCurrent()
        assertEquals(1, inspections)
        assertEquals(1, result!!.ready.size)
        repeat(50) {
            works.value = listOf(work(WorkInfo.State.SUCCEEDED, id), work(WorkInfo.State.RUNNING))
            runCurrent()
        }
        assertEquals(1, inspections)
        available = false
        refresh.value++
        runCurrent()
        assertEquals(2, inspections)
        assertNull(result!!.ready[id])
        assertEquals(1, result!!.revision)
    }

    @Test
    fun `active and failed work never reaches APK validation`() = runTest {
        val works = MutableStateFlow(listOf(work(WorkInfo.State.RUNNING)))
        val refresh = MutableStateFlow(0)
        var inspections = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            validatedAppUpdates(works, refresh) {
                inspections++
                null
            }.collect {}
        }
        runCurrent()
        works.value = listOf(work(WorkInfo.State.FAILED))
        refresh.value++
        runCurrent()
        assertEquals(0, inspections)
    }

    @Test
    fun `pending enqueue suppresses stale ready files and only the matching release is actionable`() {
        val url = "https://example.invalid/app.apk"
        val old = work(WorkInfo.State.SUCCEEDED)
        val ready = ReadyAppUpdate(old.id.toString(), File("previous.apk"))
        val pending = PendingUpdateDownload(UUID.randomUUID(), url)
        val snapshot = AppUpdateSnapshot(
            listOf(AppUpdateDownloadState(old, UpdateScreenPhase.READY, ready)),
            pending,
        )
        assertNull(snapshot.latest)
        assertEquals(UpdateScreenPhase.QUEUED, snapshot.forRelease(url).phase)
        assertEquals(UpdateScreenPhase.AVAILABLE, snapshot.forRelease("https://example.invalid/other.apk").phase)
    }

    private fun work(workState: WorkInfo.State, operation: UUID = UUID.randomUUID()) = mockk<WorkInfo> {
        every { id } returns operation
        every { state } returns workState
        every { progress } returns Data.EMPTY
        every { tags } returns emptySet()
        every { outputData } returns Data.EMPTY
    }
}

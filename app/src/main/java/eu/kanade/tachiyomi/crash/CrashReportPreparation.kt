package eu.kanade.tachiyomi.crash

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

internal sealed interface CrashReportState {
    data object Idle : CrashReportState
    data object Preparing : CrashReportState
    data class Ready(val file: File) : CrashReportState
    data object Failed : CrashReportState
}

/** Owned by the Activity ViewModel: one export survives configuration changes. */
internal class CrashReportPreparation(
    private val scope: CoroutineScope,
    private val export: suspend (Throwable?) -> File,
) {
    private val mutableState = MutableStateFlow<CrashReportState>(CrashReportState.Idle)
    val state = mutableState.asStateFlow()

    fun prepare(exception: Throwable?) {
        if (mutableState.value == CrashReportState.Preparing || mutableState.value is CrashReportState.Ready) return
        mutableState.value = CrashReportState.Preparing
        scope.launch {
            try {
                val file = export(exception)
                ensureActive()
                mutableState.value = CrashReportState.Ready(file)
            } catch (cancel: CancellationException) {
                mutableState.value = CrashReportState.Idle
                throw cancel
            } catch (_: Exception) {
                mutableState.value = CrashReportState.Failed
            }
        }
    }

    fun consumed(file: File) {
        if ((mutableState.value as? CrashReportState.Ready)?.file == file) mutableState.value = CrashReportState.Idle
    }

    fun shareFailed(file: File) {
        if ((mutableState.value as? CrashReportState.Ready)?.file == file) mutableState.value = CrashReportState.Failed
    }
}

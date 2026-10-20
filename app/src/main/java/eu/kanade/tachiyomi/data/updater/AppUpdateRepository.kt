package eu.kanade.tachiyomi.data.updater

import android.content.Context
import androidx.work.WorkInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

internal data class AppUpdateDownloadState(
    val work: WorkInfo? = null,
    val phase: UpdateScreenPhase = UpdateScreenPhase.AVAILABLE,
    val ready: ReadyAppUpdate? = null,
) {
    val progress: Int?
        get() = work?.progress?.getInt(AppUpdateDownloadJob.PROGRESS, -1)?.takeIf { it >= 0 }?.coerceAtMost(100)
}

internal data class AppUpdateSnapshot(
    val downloads: List<AppUpdateDownloadState> = emptyList(),
    val starting: PendingUpdateDownload? = null,
) {
    fun forRelease(url: String): AppUpdateDownloadState {
        if (starting?.url == url) return AppUpdateDownloadState(phase = UpdateScreenPhase.QUEUED)
        return downloads.lastOrNull { AppUpdateDownloadJob.updateTag(url) in it.work!!.tags }
            ?: AppUpdateDownloadState()
    }

    val latest: AppUpdateDownloadState?
        get() = if (starting != null) null else downloads.lastOrNull { it.phase.busy } ?: downloads.lastOrNull()
}

internal data class PendingUpdateDownload(val id: UUID, val url: String)
internal data class UpdateValidationBatch(val revision: Int, val ready: Map<UUID, ReadyAppUpdate?>)

/**
 * Validation keys exclude progress. All surfaces share one IO validation per completed operation;
 * a foreground refresh also detects deleted files and an APK that has already been installed.
 */
internal fun validatedAppUpdates(
    works: StateFlow<List<WorkInfo>>,
    refresh: StateFlow<Int>,
    validate: suspend (WorkInfo) -> ReadyAppUpdate?,
) = combine(
    works.map { list -> list.filter { it.state == WorkInfo.State.SUCCEEDED } }
        .distinctUntilChangedBy { completed ->
            completed.map { it.id to it.outputData.getString(AppUpdateDownloadJob.OUTPUT_APK_PATH) }
        },
    refresh,
) { completed, revision -> completed to revision }
    .mapLatest { (completed, revision) ->
        UpdateValidationBatch(revision, completed.associate { it.id to validate(it) })
    }

/** WorkManager remains the only download engine. No activity, cookie or installer intent is retained. */
internal class AppUpdateRepository private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refresh = MutableStateFlow(0)
    private val starting = MutableStateFlow<PendingUpdateDownload?>(null)
    private val works = AppUpdateDownloadJob.observe(context)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    private val validation = validatedAppUpdates(works, refresh) { it.readyAppUpdate(context) }
        .stateIn(scope, SharingStarted.Eagerly, UpdateValidationBatch(-1, emptyMap()))

    val state = combine(works, validation, refresh, starting) { list, checked, revision, pending ->
        val downloads = list.map { work ->
            val verified = checked.revision == revision && checked.ready.containsKey(work.id)
            // Retain the last checked file while a foreground recheck runs, avoiding reminder flicker.
            // Installation separately requires the current revision, never this retained value alone.
            val ready = checked.ready[work.id].takeIf { work.state == WorkInfo.State.SUCCEEDED }
            AppUpdateDownloadState(
                work,
                updateScreenPhase(
                    work.state,
                    verified,
                    ready != null,
                    work.progress.getString(AppUpdateDownloadJob.STAGE),
                ),
                ready,
            )
        }
        AppUpdateSnapshot(downloads, pending?.takeUnless { request -> list.any { it.id == request.id } })
    }.stateIn(scope, SharingStarted.Eagerly, AppUpdateSnapshot())

    init {
        scope.launch {
            combine(works, starting) { list, pending -> list to pending }.collect { (list, pending) ->
                if (pending != null && list.any { it.id == pending.id }) {
                    starting.update { current -> current.takeUnless { it?.id == pending.id } }
                }
            }
        }
    }

    fun refresh() {
        refresh.update { it + 1 }
    }

    fun isValidated(update: ReadyAppUpdate) =
        validation.value.revision == refresh.value &&
            validation.value.ready.values.any { it == update }

    @Synchronized
    fun start(url: String, title: String) {
        val pending = starting.value
        if (pending != null && works.value.none { it.id == pending.id }) return
        if (state.value.forRelease(url).phase.busy) return
        starting.value = PendingUpdateDownload(AppUpdateDownloadJob.start(context, url, title), url)
    }

    fun stop() {
        starting.value = null
        AppUpdateDownloadJob.stop(context)
    }

    companion object {
        @Volatile private var instance: AppUpdateRepository? = null

        fun get(context: Context): AppUpdateRepository = instance ?: synchronized(this) {
            instance ?: AppUpdateRepository(context.applicationContext).also { instance = it }
        }
    }
}

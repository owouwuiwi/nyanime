package eu.kanade.tachiyomi.data.updater

import androidx.work.WorkInfo
import eu.kanade.tachiyomi.R

enum class UpdateScreenPhase {
    AVAILABLE,
    QUEUED,
    PREPARING,
    DOWNLOADING,
    DOWNLOADING_DELTA,
    DOWNLOADING_FULL,
    REBUILDING,
    VERIFYING,
    READY,
    FAILED,
    CANCELLED,
    UNAVAILABLE,
    ;

    val busy: Boolean get() = when (this) {
        QUEUED, PREPARING, DOWNLOADING, DOWNLOADING_DELTA, DOWNLOADING_FULL, REBUILDING, VERIFYING -> true
        else -> false
    }
    val cancellable: Boolean get() = busy && this != VERIFYING
    val hasProgress: Boolean get() = when (this) {
        DOWNLOADING, DOWNLOADING_DELTA, DOWNLOADING_FULL, REBUILDING -> true
        else -> false
    }
}

internal fun UpdateScreenPhase.descriptionResource() = when (this) {
    UpdateScreenPhase.PREPARING -> R.string.app_update_delta_preparing
    UpdateScreenPhase.DOWNLOADING_DELTA -> R.string.app_update_delta_downloading
    UpdateScreenPhase.DOWNLOADING_FULL -> R.string.app_update_full_downloading
    UpdateScreenPhase.REBUILDING -> R.string.app_update_delta_rebuilding
    UpdateScreenPhase.VERIFYING -> R.string.app_update_verifying
    else -> R.string.app_update_downloading
}

/** A completed worker is not proof that its cached APK is still installable. */
internal fun updateScreenPhase(
    state: WorkInfo.State?,
    verificationFinished: Boolean,
    installable: Boolean,
    workerPhase: String? = null,
): UpdateScreenPhase = when (state) {
    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> UpdateScreenPhase.QUEUED
    WorkInfo.State.RUNNING -> UpdateScreenPhase.entries.firstOrNull {
        it.name == workerPhase &&
            it.busy &&
            it != UpdateScreenPhase.QUEUED
    }
        ?: UpdateScreenPhase.DOWNLOADING
    WorkInfo.State.SUCCEEDED -> when {
        !verificationFinished -> UpdateScreenPhase.VERIFYING
        installable -> UpdateScreenPhase.READY
        else -> UpdateScreenPhase.UNAVAILABLE
    }
    WorkInfo.State.FAILED -> UpdateScreenPhase.FAILED
    WorkInfo.State.CANCELLED -> UpdateScreenPhase.CANCELLED
    null -> UpdateScreenPhase.AVAILABLE
}

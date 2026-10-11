package eu.kanade.tachiyomi.data.updater

import androidx.work.WorkInfo

enum class UpdateScreenPhase {
    AVAILABLE,
    QUEUED,
    DOWNLOADING,
    VERIFYING,
    READY,
    FAILED,
    CANCELLED,
    UNAVAILABLE,
    ;

    val busy: Boolean get() = this == QUEUED || this == DOWNLOADING || this == VERIFYING
    val cancellable: Boolean get() = this == QUEUED || this == DOWNLOADING
}

/** A completed worker is not proof that its cached APK is still installable. */
internal fun updateScreenPhase(
    state: WorkInfo.State?,
    verificationFinished: Boolean,
    installable: Boolean,
): UpdateScreenPhase = when (state) {
    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> UpdateScreenPhase.QUEUED
    WorkInfo.State.RUNNING -> UpdateScreenPhase.DOWNLOADING
    WorkInfo.State.SUCCEEDED -> when {
        !verificationFinished -> UpdateScreenPhase.VERIFYING
        installable -> UpdateScreenPhase.READY
        else -> UpdateScreenPhase.UNAVAILABLE
    }
    WorkInfo.State.FAILED -> UpdateScreenPhase.FAILED
    WorkInfo.State.CANCELLED -> UpdateScreenPhase.CANCELLED
    null -> UpdateScreenPhase.AVAILABLE
}

package eu.kanade.tachiyomi.data.download.transfer

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR

fun TransferWait.label(context: Context): String = context.stringResource(
    when (this) {
        TransferWait.MANUAL -> AYMR.strings.transfer_wait_manual
        TransferWait.PLAYBACK -> AYMR.strings.transfer_wait_playback
        TransferWait.NETWORK -> AYMR.strings.transfer_wait_network
        TransferWait.WIFI -> AYMR.strings.transfer_wait_wifi
        TransferWait.SYSTEM -> AYMR.strings.transfer_wait_system
    },
)

fun TransferSnapshot.label(context: Context, progress: String): String = when (phase) {
    TransferPhase.QUEUED -> context.stringResource(MR.strings.ext_pending)
    TransferPhase.WAITING -> wait?.label(context) ?: progress
    TransferPhase.PREPARING -> context.stringResource(AYMR.strings.transfer_preparing)
    TransferPhase.PROCESSING -> context.stringResource(AYMR.strings.transfer_processing)
    TransferPhase.SAVING -> context.stringResource(AYMR.strings.transfer_saving)
    TransferPhase.FAILED -> context.stringResource(AYMR.strings.download_error)
    TransferPhase.TRANSFERRING -> buildList {
        add(progress)
        if (bytes > 0) {
            val current = Formatter.formatShortFileSize(context, bytes)
            add(if (total > 0) "$current / ${Formatter.formatShortFileSize(context, total)}" else current)
        }
        if (bytesPerSecond > 0) add("${Formatter.formatShortFileSize(context, bytesPerSecond)}/s")
        secondsRemaining?.let {
            add(context.stringResource(AYMR.strings.transfer_remaining, DateUtils.formatElapsedTime(it)))
        }
    }.joinToString(" · ")
    else -> progress
}

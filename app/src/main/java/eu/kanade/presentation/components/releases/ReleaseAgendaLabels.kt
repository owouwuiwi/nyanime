package eu.kanade.presentation.components.releases

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.releases.AiringIssue
import eu.kanade.tachiyomi.data.releases.ReleaseAgendaLabel

@Composable
internal fun ReleaseAgendaLabel.displayText(): String = displayText(LocalContext.current)

internal fun ReleaseAgendaLabel.displayText(context: Context): String = when (this) {
    is ReleaseAgendaLabel.OriginalBroadcast -> scheduleBroadcastLabel(context, event)
    is ReleaseAgendaLabel.PlannedChapter -> context.getString(R.string.release_chapter_planned, number.toString())
    is ReleaseAgendaLabel.Available -> context.getString(R.string.release_content_available, itemName)
}

internal fun AiringIssue.Reason.agendaWarningResource(): Int = when (this) {
    AiringIssue.Reason.UNAVAILABLE -> R.string.release_calendar_error
    AiringIssue.Reason.UNVERIFIED_ID -> R.string.release_calendar_unresolved
    AiringIssue.Reason.PENDING -> R.string.release_calendar_pending
}

package eu.kanade.presentation.entries.details

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.entries.DownloadAction
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.lang.toRelativeString
import eu.kanade.tachiyomi.util.system.copyToClipboard
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.pluralStringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import tachiyomi.presentation.core.i18n.stringResource as sharedStringResource

@Composable
internal fun DetailMetadata(
    title: String,
    author: String?,
    artist: String?,
    source: String,
    onSearch: (String, Boolean) -> Unit,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.detail_metadata), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        SelectionContainer { Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        listOfNotNull(
            author?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.detail_author) to it },
            artist?.takeIf { it.isNotBlank() && it != author }?.let { stringResource(R.string.detail_artist) to it },
        ).forEach { (label, value) ->
            Column(
                Modifier.fillMaxWidth().combinedClickable(onClick = {
                    onSearch(value, true)
                }, onLongClick = { context.copyToClipboard(value, value) }).padding(vertical = 4.dp),
            ) {
                Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, fontSize = 14.sp)
            }
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(
                stringResource(R.string.detail_source),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer { Text(source, fontSize = 14.sp) }
        }
    }
}

@Composable
internal fun DetailDownloadActions(manga: Boolean, onDownload: ((DownloadAction) -> Unit)?): List<DetailAction> {
    if (onDownload == null) return emptyList()
    val amount = if (manga) MR.plurals.download_amount else AYMR.plurals.download_amount_anime
    return listOf(
        1 to DownloadAction.NEXT_1_ITEM,
        5 to DownloadAction.NEXT_5_ITEMS,
        10 to DownloadAction.NEXT_10_ITEMS,
        25 to DownloadAction.NEXT_25_ITEMS,
    ).map { (count, action) ->
        DetailAction(pluralStringResource(amount, count, count), TitleDetailsIcons.Download) { onDownload(action) }
    } +
        DetailAction(
            sharedStringResource(if (manga) MR.strings.download_unread else AYMR.strings.download_unseen),
            TitleDetailsIcons.Download,
        ) { onDownload(DownloadAction.UNVIEWED_ITEMS) }
}

/** One formatter per screen instead of composing the whole chapter catalogue on every frame. */
@Composable
internal fun rememberDetailDateFormatter(): (Long) -> String {
    val context = LocalContext.current
    val preferences = remember { Injekt.get<UiPreferences>() }
    val relative = remember { preferences.relativeTime().get() }
    val format = remember { UiPreferences.dateFormat(preferences.dateFormat().get()) }
    return remember(context, relative, format) {
        { millis ->
            if (millis <= 0) {
                ""
            } else {
                LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
                    .toRelativeString(context, relative, format)
            }
        }
    }
}

@Composable
internal fun detailStatus(status: Long): String = sharedStringResource(
    when (status.toInt()) {
        1 -> MR.strings.ongoing
        2 -> MR.strings.completed
        3 -> MR.strings.licensed
        4 -> MR.strings.publishing_finished
        5 -> MR.strings.cancelled
        6 -> MR.strings.on_hiatus
        7 -> AYMR.strings.upcoming
        else -> MR.strings.unknown
    },
)

package eu.kanade.presentation.more.settings.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.LocalPreferenceHighlighted
import eu.kanade.presentation.track.components.TrackLogoIcon
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun TrackingPreferenceWidget(
    modifier: Modifier = Modifier,
    tracker: Tracker,
    checked: Boolean,
    onClick: (() -> Unit)? = null,
) {
    val highlighted = LocalPreferenceHighlighted.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val account = tracker as? BaseTracker
    val refreshing = account?.isRefreshingAccount?.collectAsState()?.value ?: false
    val displayName = if (account != null) {
        val preference = account.trackPreferences.trackDisplayName(account)
        val name by preference.changes().collectAsState(preference.get())
        name
    } else {
        ""
    }
    Box(modifier = Modifier.highlightBackground(highlighted)) {
        Row(
            modifier = modifier
                .clickable(enabled = onClick != null, onClick = { onClick?.invoke() })
                .fillMaxWidth()
                .padding(horizontal = PrefsHorizontalPadding, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrackLogoIcon(tracker)
            Column(Modifier.weight(1f)) {
                Text(
                    text = tracker.name,
                    modifier = Modifier
                        .padding(horizontal = 16.dp),
                    maxLines = 1,
                    style = MaterialTheme.typography.titleLarge,
                    fontSize = TitleFontSize,
                )
                if (checked && displayName.isNotBlank()) {
                    Text(
                        displayName,
                        Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (checked && account?.supportsAccountRefresh == true) {
                IconButton(enabled = !refreshing, onClick = {
                    scope.launchIO {
                        try {
                            if (account.refreshAccountSettings()) {
                                withUIContext {
                                    context.toast(MR.strings.tracker_account_updated)
                                }
                            }
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            withUIContext { context.toast(MR.strings.tracker_account_failed) }
                        }
                    }
                }) {
                    if (refreshing) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = stringResource(MR.strings.tracker_refresh_account),
                        )
                    }
                }
            }
            if (checked) {
                Icon(
                    imageVector = Icons.Outlined.Done,
                    modifier = Modifier
                        .padding(4.dp)
                        .size(32.dp),
                    tint = Color(0xFF4CAF50),
                    contentDescription = stringResource(MR.strings.login_success),
                )
            }
        }
    }
}

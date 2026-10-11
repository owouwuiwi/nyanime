package eu.kanade.presentation.privacy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.privacy.PrivacyArea
import eu.kanade.tachiyomi.ui.privacy.PrivacyDisplayPresentation
import eu.kanade.tachiyomi.ui.privacy.PrivacyDisplayRuntime
import nyanime.privacy.display.PrivacyDisplayCapability
import nyanime.privacy.display.PrivacyDisplayState
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.stringResource

/** Player and reader share the same window-owned, temporary control. */
@Composable
fun PrivacySessionEntry(area: PrivacyArea, sessionDescription: String, horizontalPadding: Dp = 16.dp) {
    val controller = LocalContext.current.baseActivity()?.privacyDisplayController ?: return
    val state by controller.state.collectAsState()
    val policy by controller.configuration.collectAsState()
    val failure by PrivacyDisplayRuntime.failure.collectAsState()
    val capability = PrivacyDisplayRuntime.capability()
    val presentation = PrivacyDisplayPresentation.from(policy, capability, state, area, failed = failure != null)
    PrivacySessionControl(
        presentation,
        capability,
        state,
        sessionDescription,
        onToggle = { controller.setTemporaryOverride(area, it) },
        horizontalPadding = horizontalPadding,
    )
}

@Composable
internal fun PrivacySessionControl(
    presentation: PrivacyDisplayPresentation,
    capability: PrivacyDisplayCapability,
    state: PrivacyDisplayState,
    sessionDescription: String,
    onToggle: (Boolean) -> Unit,
    horizontalPadding: Dp = 16.dp,
) {
    PrivacyToggleRow(
        title = stringResource(AYMR.strings.privacy_display_title),
        subtitle = privacyStatusDescription(presentation, capability, state) + "\n" + sessionDescription,
        checked = presentation.requested,
        enabled = presentation.canToggle,
        onToggle = onToggle,
        horizontalPadding = horizontalPadding,
    )
}

package eu.kanade.presentation.privacy

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.privacy.PrivacyArea
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun PlayerPrivacyEntry() = PrivacySessionEntry(
    area = PrivacyArea.VIDEO,
    sessionDescription = stringResource(AYMR.strings.privacy_display_player_session),
    horizontalPadding = 0.dp,
)

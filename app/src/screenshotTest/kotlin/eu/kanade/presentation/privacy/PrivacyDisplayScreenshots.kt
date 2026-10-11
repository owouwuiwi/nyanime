package eu.kanade.presentation.privacy

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.widget.InfoWidget
import eu.kanade.presentation.more.settings.widget.PreferenceGroupHeader
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.ui.privacy.PrivacyArea
import eu.kanade.tachiyomi.ui.privacy.PrivacyDisplayPolicy
import eu.kanade.tachiyomi.ui.privacy.PrivacyDisplayPresentation
import nyanime.privacy.display.PrivacyDisplayCapability
import nyanime.privacy.display.PrivacyDisplayState
import nyanime.privacy.display.PrivacyUnavailableReason
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.stringResource

@PreviewTest
@Preview(name = "PrivacyModernUnsupported", widthDp = 360, heightDp = 1000, locale = "it")
@Preview(name = "PrivacyNarrowLargeText", widthDp = 280, heightDp = 1300, fontScale = 1.5f, locale = "it")
@Composable
fun PrivacyUnsupportedPreview() = PrivacyPreview(false, true, true)

@PreviewTest
@Preview(name = "PrivacyLegacyUnsupported", widthDp = 320, heightDp = 1000, locale = "it")
@Composable
fun PrivacyLegacyPreview() = PrivacyPreview(false, false, false)

@PreviewTest
@Preview(name = "PrivacyEnabled", widthDp = 393, heightDp = 1000, locale = "it")
@Preview(name = "PrivacyEnabledNarrowLargeText", widthDp = 280, heightDp = 1500, fontScale = 1.5f, locale = "it")
@Composable
fun PrivacyEnabledPreview() = PrivacyPreview(true, true, true)

@Composable
private fun PrivacyPreview(available: Boolean, modern: Boolean, dark: Boolean) {
    TachiyomiPreviewTheme(modernUi = modern, darkTheme = dark) {
        Scaffold(topBar = { AppBar(title = "Sicurezza", navigateUp = {}) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
                PreferenceGroupHeader(stringResource(AYMR.strings.privacy_display_title))
                PrivacyToggleRow(
                    title = stringResource(AYMR.strings.privacy_display_enable),
                    subtitle = stringResource(
                        if (available) {
                            AYMR.strings.privacy_display_available
                        } else {
                            AYMR.strings.privacy_display_no_hardware
                        },
                    ),
                    checked = available,
                    enabled = available,
                    onToggle = {},
                )
                if (available) {
                    PrivacyToggleRow(
                        title = stringResource(AYMR.strings.privacy_display_only_incognito),
                        subtitle = stringResource(AYMR.strings.privacy_display_only_incognito_summary),
                        checked = false,
                        enabled = true,
                        onToggle = {},
                    )
                    PrivacyArea.entries.forEach { area ->
                        PrivacyToggleRow(
                            title = privacyAreaTitle(area),
                            checked = area != PrivacyArea.NSFW,
                            enabled = true,
                            onToggle = {},
                        )
                    }
                }
                InfoWidget(stringResource(AYMR.strings.privacy_display_summary))
            }
        }
    }
}

@PreviewTest
@Preview(name = "PrivacyReaderIncognitoNarrow", widthDp = 280, heightDp = 520, fontScale = 1.5f, locale = "it")
@Composable
fun PrivacyReaderIncognitoPreview() = PrivacySessionPreview(true, true)

@PreviewTest
@Preview(name = "PrivacyReaderLegacyUnsupported", widthDp = 280, heightDp = 520, fontScale = 1.5f, locale = "it")
@Composable
fun PrivacyReaderUnsupportedPreview() = PrivacySessionPreview(false, false)

@Composable
private fun PrivacySessionPreview(available: Boolean, modern: Boolean) {
    val capability = if (available) {
        PrivacyDisplayCapability.Available
    } else {
        PrivacyDisplayCapability.Unavailable(PrivacyUnavailableReason.HARDWARE)
    }
    val state = PrivacyDisplayState.Disabled
    val policy = PrivacyDisplayPolicy(available, setOf(PrivacyArea.READER), onlyInIncognito = true)
    TachiyomiPreviewTheme(modernUi = modern, darkTheme = modern) {
        Scaffold(topBar = { AppBar(title = "Lettore", navigateUp = {}) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
                PrivacySessionControl(
                    PrivacyDisplayPresentation.from(policy, capability, state, PrivacyArea.READER),
                    capability,
                    state,
                    stringResource(AYMR.strings.privacy_display_reader_session),
                    onToggle = {},
                )
            }
        }
    }
}

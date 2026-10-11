package eu.kanade.presentation.privacy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.ui.privacy.PrivacyArea
import eu.kanade.tachiyomi.ui.privacy.PrivacyDisplayPolicy
import eu.kanade.tachiyomi.ui.privacy.PrivacyDisplayPreferences
import eu.kanade.tachiyomi.ui.privacy.PrivacyDisplayPresentation
import eu.kanade.tachiyomi.ui.privacy.PrivacyDisplayRuntime
import eu.kanade.tachiyomi.ui.privacy.PrivacyDisplayStatus
import kotlinx.collections.immutable.toImmutableList
import nyanime.privacy.display.PrivacyDisplayCapability
import nyanime.privacy.display.PrivacyDisplayState
import nyanime.privacy.display.PrivacyUnavailableReason
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun privacyDisplayPreferences(): Preference.PreferenceGroup {
    val preferences = remember { Injekt.get<PrivacyDisplayPreferences>() }
    val requested by preferences.enabled().collectAsState()
    val failure by PrivacyDisplayRuntime.failure.collectAsState()
    val capability = remember(failure) { PrivacyDisplayRuntime.capability() }
    val available = capability == PrivacyDisplayCapability.Available
    val controller = LocalContext.current.baseActivity()?.privacyDisplayController
    val policy = controller?.configuration?.collectAsState()?.value ?: PrivacyDisplayPolicy(requested, emptySet())
    val state = controller?.state?.collectAsState()?.value ?: PrivacyDisplayState.Disabled
    val presentation = PrivacyDisplayPresentation.from(policy, capability, state, failed = failure != null)
    return Preference.PreferenceGroup(
        title = stringResource(AYMR.strings.privacy_display_title),
        preferenceItems = (
            listOf(
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(AYMR.strings.privacy_display_enable),
                ) {
                    PrivacyToggleRow(
                        title = stringResource(AYMR.strings.privacy_display_enable),
                        subtitle = privacyStatusDescription(presentation, capability, state),
                        checked = requested,
                        enabled = available,
                        onToggle = preferences.enabled()::set,
                    )
                },
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(AYMR.strings.privacy_display_only_incognito),
                ) {
                    val onlyInIncognito by preferences.onlyInIncognito().collectAsState()
                    AnimatedVisibility(
                        visible = requested && available,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        PrivacyToggleRow(
                            title = stringResource(AYMR.strings.privacy_display_only_incognito),
                            subtitle = stringResource(AYMR.strings.privacy_display_only_incognito_summary),
                            checked = onlyInIncognito,
                            enabled = available,
                            onToggle = preferences.onlyInIncognito()::set,
                        )
                    }
                },
            ) +
                PrivacyArea.entries.map { area ->
                    val areaPreference = remember(area) { preferences.area(area) }
                    Preference.PreferenceItem.CustomPreference(
                        title = privacyAreaTitle(area),
                    ) {
                        val selected by areaPreference.collectAsState()
                        AnimatedVisibility(
                            visible = requested && available,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut(),
                        ) {
                            PrivacyToggleRow(
                                title = privacyAreaTitle(area),
                                checked = selected,
                                enabled = available,
                                onToggle = areaPreference::set,
                            )
                        }
                    }
                } +
                Preference.PreferenceItem.InfoPreference(stringResource(AYMR.strings.privacy_display_summary))
            ).toImmutableList(),
    )
}

@Composable
internal fun privacyAreaTitle(area: PrivacyArea): String = stringResource(
    when (area) {
        PrivacyArea.VIDEO -> AYMR.strings.privacy_display_video
        PrivacyArea.READER -> AYMR.strings.privacy_display_reader
        PrivacyArea.DETAILS -> AYMR.strings.privacy_display_details
        PrivacyArea.LIBRARY -> AYMR.strings.privacy_display_library
        PrivacyArea.HISTORY -> AYMR.strings.privacy_display_history
        PrivacyArea.RESUME -> AYMR.strings.privacy_display_resume
        PrivacyArea.SEARCH -> AYMR.strings.privacy_display_search
        PrivacyArea.NSFW -> AYMR.strings.privacy_display_nsfw
    },
)

@Composable
fun privacyCapabilityDescription(capability: PrivacyDisplayCapability): String = stringResource(
    when ((capability as? PrivacyDisplayCapability.Unavailable)?.reason) {
        PrivacyUnavailableReason.HARDWARE -> AYMR.strings.privacy_display_no_hardware
        PrivacyUnavailableReason.FIRMWARE -> AYMR.strings.privacy_display_no_firmware
        PrivacyUnavailableReason.DISPLAY -> AYMR.strings.privacy_display_no_display
        PrivacyUnavailableReason.WINDOW_MODE -> AYMR.strings.privacy_display_no_window
        null -> AYMR.strings.privacy_display_available
    },
)

@Composable
internal fun privacyStatusDescription(
    presentation: PrivacyDisplayPresentation,
    capability: PrivacyDisplayCapability,
    state: PrivacyDisplayState,
): String = when (presentation.status) {
    PrivacyDisplayStatus.UNAVAILABLE -> privacyCapabilityDescription(capability)
    PrivacyDisplayStatus.SUSPENDED -> stringResource(
        AYMR.strings.privacy_display_suspended,
        privacyCapabilityDescription(
            PrivacyDisplayCapability.Unavailable((state as PrivacyDisplayState.Unavailable).reason),
        ),
    )
    PrivacyDisplayStatus.DISABLED -> stringResource(AYMR.strings.privacy_display_disabled)
    PrivacyDisplayStatus.WAITING_FOR_INCOGNITO -> stringResource(AYMR.strings.privacy_display_waiting_incognito)
    PrivacyDisplayStatus.WAITING_FOR_AREA -> stringResource(AYMR.strings.privacy_display_waiting_area)
    PrivacyDisplayStatus.REQUESTED -> stringResource(AYMR.strings.privacy_display_applied)
    PrivacyDisplayStatus.FAILED -> stringResource(AYMR.strings.privacy_display_failed)
}

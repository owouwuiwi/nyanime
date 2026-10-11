// SPDX-License-Identifier: GPL-3.0-only
// Derived from FLOW 14afdf9e9426fb8b67e5fcbc76bf4e81af492d16.
// Modified for Nyanime: UI-only, host-owned playback, no service/runtime access.
// Original source identities and changes: presentation-player/third-party/flow-ui/PROVENANCE.json.

package it.lorelore.nyanime.presentation.player.flow.ui.components.videoplayer.controls

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import it.lorelore.nyanime.presentation.player.R

internal fun resolvePlayerQualityLabel(
    currentQuality: Int,
    effectiveQuality: Int,
    autoLabel: String,
    autoWithHeightLabel: String,
): String =
    when {
        currentQuality > 0 -> currentQuality.toString()
        effectiveQuality > 0 -> autoWithHeightLabel
        else -> autoLabel
    }

/**
 * What the quality pill shows, before it is turned into text.
 *
 * The ladder is kept resource-free so it stays a pure function: the marketing names are localisable
 * strings, the pixel heights are a template, and anything the ladder does not recognise is passed
 * through as the extractor wrote it.
 */
internal sealed interface PlayerQualityBadge {
    /** A named tier: 4K, QHD, FHD, HD, SD. */
    data class Named(
        @param:StringRes val labelRes: Int,
    ) : PlayerQualityBadge

    /** A recognised height with no marketing name, rendered as `<height>p`. */
    data class Height(
        val height: Int,
    ) : PlayerQualityBadge

    /** A label the ladder could not read a height out of, shown unchanged. */
    data class Verbatim(
        val text: String,
    ) : PlayerQualityBadge
}

internal fun compactPlayerQualityBadge(qualityLabel: String): PlayerQualityBadge {
    val height =
        Regex("""\d+""")
            .find(qualityLabel)
            ?.value
            ?.toIntOrNull()
            ?.let(::normalizeQualityHeight)
    return when (height) {
        2160 -> PlayerQualityBadge.Named(R.string.flow_filter_4k)
        1440 -> PlayerQualityBadge.Named(R.string.flow_quality_badge_qhd)
        1080 -> PlayerQualityBadge.Named(R.string.flow_quality_badge_fhd)
        720 -> PlayerQualityBadge.Named(R.string.flow_filter_hd)
        480 -> PlayerQualityBadge.Named(R.string.flow_quality_badge_sd)
        null -> PlayerQualityBadge.Verbatim(qualityLabel)
        else -> PlayerQualityBadge.Height(height)
    }
}

@Composable
internal fun playerQualityBadgeLabel(badge: PlayerQualityBadge): String =
    when (badge) {
        is PlayerQualityBadge.Named -> stringResource(badge.labelRes)
        is PlayerQualityBadge.Height -> stringResource(R.string.flow_quality_badge_height_template, badge.height)
        is PlayerQualityBadge.Verbatim -> badge.text
    }

private fun normalizeQualityHeight(value: Int): Int = value.coerceAtLeast(0)

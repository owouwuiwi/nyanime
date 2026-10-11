package eu.kanade.tachiyomi.ui.addon

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import eu.kanade.domain.extension.AddonHostVersions
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R

@Composable
fun AddonCompatibilityNotice(bounds: AddonHostVersions) {
    val minimum = bounds.minimum?.let { bounds.minimumName ?: stringResource(R.string.addon_host_build, it) }
    val maximum = bounds.maximum?.let { bounds.maximumName ?: stringResource(R.string.addon_host_build, it) }
    val text = when (bounds.status(BuildConfig.VERSION_CODE.toLong())) {
        AddonHostVersions.Status.TOO_OLD -> stringResource(
            R.string.addon_host_too_old,
            requireNotNull(minimum),
            BuildConfig.VERSION_NAME,
        )
        AddonHostVersions.Status.TOO_NEW -> stringResource(
            R.string.addon_host_too_new,
            requireNotNull(maximum),
            BuildConfig.VERSION_NAME,
        )
        AddonHostVersions.Status.INVALID -> stringResource(R.string.addon_incompatible)
        AddonHostVersions.Status.COMPATIBLE -> when {
            minimum != null && maximum != null -> stringResource(R.string.addon_host_range, minimum, maximum)
            minimum != null -> stringResource(R.string.addon_host_minimum, minimum)
            maximum != null -> stringResource(R.string.addon_host_maximum, maximum)
            else -> null
        }
    }
    text?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

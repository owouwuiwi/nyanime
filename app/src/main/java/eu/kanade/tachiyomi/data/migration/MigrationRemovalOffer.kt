package eu.kanade.tachiyomi.data.migration

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.system.toast

internal object MigrationRemovalOffer {
    fun shouldOffer(context: Context) = MigrationRemovalPolicy.shouldOffer(
        MigrationState.mayRemoveLegacy(context),
        MigrationState.state(context).optBoolean("removalOfferHandled"),
    )

    fun acknowledge(context: Context) = MigrationState.update(context) { it.put("removalOfferHandled", true) }

    fun request(context: Context): Boolean {
        // Never pass an unverified package or a pending transfer to the Android installer.
        if (!MigrationState.mayRemoveLegacy(context)) return false
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:${BuildConfig.LEGACY_APPLICATION_ID}")),
            )
            true
        }.recoverCatching {
            context.startActivity(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${BuildConfig.LEGACY_APPLICATION_ID}"),
                ),
            )
            true
        }.getOrElse {
            context.toast(context.getString(R.string.migration_remove_failed))
            false
        }
    }
}

@Composable
internal fun MigrationRemovalDialog(onLater: () -> Unit, onRemove: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.migration_remove_title)) },
        text = {
            Text(
                stringResource(R.string.migration_remove_body),
                Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            Button(onClick = onRemove, colors = ButtonDefaults.buttonColors(contentColor = Color.White)) {
                Text(stringResource(R.string.migration_remove))
            }
        },
        dismissButton = {
            TextButton(onClick = onLater) {
                Text(stringResource(R.string.migration_not_now), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

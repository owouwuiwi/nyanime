package eu.kanade.tachiyomi.ui.main

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.migration.MigrationInstallDialog
import eu.kanade.tachiyomi.data.migration.MigrationInstallOffer
import eu.kanade.tachiyomi.data.migration.MigrationRemovalDialog
import eu.kanade.tachiyomi.data.migration.MigrationRemovalOffer
import eu.kanade.tachiyomi.data.updater.RELEASE_URL
import eu.kanade.tachiyomi.util.system.openInBrowser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun MainStartupNotices(
    context: Context,
    scope: CoroutineScope,
    wasConfigured: Boolean,
    didMigration: Boolean,
) {
    var showChangelog by remember { mutableStateOf(didMigration && wasConfigured && !BuildConfig.DEBUG) }
    var showRemovalOffer by remember { mutableStateOf(false) }
    var removalOfferChecked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        showRemovalOffer = withContext(Dispatchers.IO) {
            MigrationRemovalOffer.shouldOffer(context)
        }
        if (showRemovalOffer) showChangelog = false
        removalOfferChecked = true
    }
    if (showRemovalOffer) {
        fun closeRemovalOffer() {
            MigrationRemovalOffer.acknowledge(context)
            showRemovalOffer = false
        }
        MigrationRemovalDialog(
            onLater = ::closeRemovalOffer,
            onRemove = {
                if (MigrationRemovalOffer.request(context)) {
                    closeRemovalOffer()
                }
            },
        )
    }
    if (showChangelog && !showRemovalOffer && removalOfferChecked) {
        val installOffer = remember {
            MigrationInstallOffer.shouldOffer(
                context,
                wasConfigured,
                didMigration,
            )
        }
        if (installOffer) {
            fun closeOffer() {
                MigrationInstallOffer.acknowledge(context)
                showChangelog = false
            }
            MigrationInstallDialog(
                onLater = ::closeOffer,
                onContinue = {
                    scope.launch {
                        if (MigrationInstallOffer.open(
                                context,
                                stillRequested = { showChangelog },
                            )
                        ) {
                            closeOffer()
                        }
                    }
                },
            )
        } else {
            AlertDialog(
                onDismissRequest = { showChangelog = false },
                title = {
                    Text(
                        text = stringResource(MR.strings.updated_version, BuildConfig.VERSION_NAME),
                    )
                },
                dismissButton = {
                    TextButton(onClick = { context.openInBrowser(RELEASE_URL) }) {
                        Text(text = stringResource(MR.strings.whats_new))
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showChangelog = false }) {
                        Text(text = stringResource(MR.strings.action_ok))
                    }
                },
            )
        }
    }
}

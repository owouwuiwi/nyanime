package eu.kanade.tachiyomi.ui.main

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.migration.MigrationInstallDialog
import eu.kanade.tachiyomi.data.migration.MigrationInstallOffer
import eu.kanade.tachiyomi.data.migration.MigrationRemovalDialog
import eu.kanade.tachiyomi.data.migration.MigrationRemovalOffer
import eu.kanade.tachiyomi.ui.more.InstalledUpdateDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun MainStartupNotices(
    context: Context,
    scope: CoroutineScope,
    wasConfigured: Boolean,
    didMigration: Boolean,
) {
    var showChangelog by rememberSaveable { mutableStateOf(didMigration && wasConfigured && !BuildConfig.DEBUG) }
    val installOffer = rememberSaveable {
        showChangelog && MigrationInstallOffer.shouldOffer(context, wasConfigured, didMigration)
    }
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
            InstalledUpdateDialog { showChangelog = false }
        }
    }
}

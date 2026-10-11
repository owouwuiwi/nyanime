package eu.kanade.tachiyomi.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import eu.kanade.presentation.more.settings.screen.browse.components.CatalogueResetGate
import eu.kanade.tachiyomi.data.extensionstore.CatalogueUpgradeReset
import eu.kanade.tachiyomi.ui.more.InitialAppSetupGate

@Composable
internal fun MainStartup(
    catalogueReset: CatalogueUpgradeReset,
    onReady: () -> Unit,
    onExit: () -> Unit,
    content: @Composable () -> Unit,
) {
    var resetPending by remember(catalogueReset) { mutableStateOf(catalogueReset.pending) }
    if (resetPending) {
        LaunchedEffect(Unit) { onReady() }
        CatalogueResetGate(
            reset = catalogueReset,
            onComplete = { resetPending = false },
            onExit = onExit,
        )
    } else {
        InitialAppSetupGate(onReady = onReady, content = content)
    }
}

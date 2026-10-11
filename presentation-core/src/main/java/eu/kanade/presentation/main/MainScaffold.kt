package eu.kanade.presentation.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import eu.kanade.presentation.components.AppStateBanners
import tachiyomi.presentation.core.components.material.Scaffold

@Immutable
data class MainBannerState(
    val downloadedOnly: Boolean,
    val incognito: Boolean,
    val indexing: Boolean,
)

/** Renders the activity's shared UI from state and slots supplied by its navigation host. */
@Composable
fun MainScaffold(
    banners: MainBannerState,
    showNavigationScrim: Boolean,
    bottomBar: @Composable () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val scaffoldInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)
    Scaffold(
        topBar = {
            AppStateBanners(
                downloadedOnlyMode = banners.downloadedOnly,
                incognitoMode = banners.incognito,
                indexing = banners.indexing,
                modifier = Modifier.windowInsetsPadding(scaffoldInsets),
            )
        },
        bottomBar = bottomBar,
        contentWindowInsets = scaffoldInsets,
    ) { padding ->
        Box {
            content(Modifier.padding(padding).consumeWindowInsets(padding))
            if (showNavigationScrim) {
                Spacer(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .windowInsetsBottomHeight(WindowInsets.navigationBars)
                        .alpha(0.8f)
                        .background(MaterialTheme.colorScheme.surfaceContainer),
                )
            }
        }
    }
}

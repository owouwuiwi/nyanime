package eu.kanade.presentation.discovery

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import tachiyomi.domain.discovery.SourceHomeGroup

@PreviewTest
@Preview(name = "ContinuityDark", widthDp = 360, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "ContinuityLight", widthDp = 360, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "ContinuityLargeText", widthDp = 320, heightDp = 720, fontScale = 1.5f)
@Composable
fun HomeContinuityInitialScreenshot() = ContinuityPreview(scrolled = false)

@PreviewTest
@Preview(name = "ContinuityScrolled", widthDp = 360, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun HomeContinuityScrolledScreenshot() = ContinuityPreview(scrolled = true)

@Composable
private fun ContinuityPreview(scrolled: Boolean) {
    TachiyomiPreviewTheme(appTheme = AppTheme.NYANIME) {
        val state = remember { HomeContinuityState() }
        CompositionLocalProvider(LocalHomeContinuity provides state) {
            Scaffold(
                topBar = {
                    HomeContinuityHeader(state, 0.dp) {
                        DiscoveryHomeHeader(
                            selectedHome = "films",
                            onSelect = {},
                            onBack = null,
                            onSearch = null,
                            onRefresh = {},
                            homes = listOf(
                                SourceHomeGroup("films", "Film", emptyList()),
                                SourceHomeGroup("stories", "Storie", emptyList()),
                            ),
                        )
                    }
                },
            ) { padding ->
                LazyColumn(
                    Modifier.fillMaxSize().homeContinuitySource(state),
                    state = rememberLazyListState(
                        initialFirstVisibleItemIndex = if (scrolled) 2 else 0,
                        initialFirstVisibleItemScrollOffset = with(LocalDensity.current) {
                            if (scrolled) 120.dp.roundToPx() else 0
                        },
                    ),
                    contentPadding = PaddingValues(top = padding.calculateTopPadding()),
                ) {
                    items(8) { index ->
                        Box(
                            Modifier.fillMaxWidth().height(180.dp).background(
                                Brush.horizontalGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primaryContainer,
                                        MaterialTheme.colorScheme.tertiary,
                                    ),
                                ),
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "Titolo ${index + 1}",
                                Modifier.padding(24.dp),
                                style = MaterialTheme.typography.headlineMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

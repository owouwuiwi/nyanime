package eu.kanade.presentation.discovery

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import tachiyomi.domain.discovery.SourceHomeFilter
import tachiyomi.domain.discovery.SourceHomeGroup

@PreviewTest
@Preview(name = "Filters", widthDp = 393, heightDp = 800, locale = "it")
@Preview(name = "FiltersNarrow", widthDp = 320, heightDp = 740, fontScale = 1.4f, locale = "it")
@Composable
fun SourceHomeBrowseFiltersScreenshot() {
    TachiyomiPreviewTheme(appTheme = AppTheme.NYANIME) {
        Surface(Modifier.fillMaxSize()) {
            SourceHomeFilterContent(
                listOf(
                    SourceHomeFilter(
                        "Generi",
                        SourceHomeFilter.Kind.MULTIPLE,
                        listOf("Avventura", "Azione", "Commedia", "Fantascienza", "Fantasy", "Mistero", "Romantico"),
                    ),
                    SourceHomeFilter(
                        "Anno",
                        SourceHomeFilter.Kind.SINGLE,
                        listOf("Tutti", "2026", "2025"),
                        listOf("Tutti"),
                    ),
                    SourceHomeFilter(
                        "Stato",
                        SourceHomeFilter.Kind.SINGLE,
                        listOf("Tutti", "In corso", "Conclusi"),
                        listOf("Tutti"),
                    ),
                    SourceHomeFilter("Audio", SourceHomeFilter.Kind.MULTIPLE, listOf("Italiano", "Giapponese")),
                    SourceHomeFilter(
                        "Ordine",
                        SourceHomeFilter.Kind.SINGLE,
                        listOf("Più recenti", "Più visti", "Valutazione"),
                        listOf("Più recenti"),
                    ),
                ),
                mapOf("Generi" to listOf("Avventura", "Fantasy")),
                {},
            )
        }
    }
}

@PreviewTest
@Preview(name = "HomeCategories", widthDp = 393, heightDp = 260, locale = "it")
@Composable
fun SourceHomeBrowseCategoriesScreenshot() {
    TachiyomiPreviewTheme(appTheme = AppTheme.NYANIME) {
        Surface {
            Column {
                DiscoveryHomeHeader(null, {}, null, {}, {}, emptyList())
                SourceHomeExploreBar(
                    listOf("Avventura", "Azione", "Commedia").map { SourceHomeGroup.Section(it, it) },
                    {},
                    {},
                )
                Column(Modifier.padding(horizontal = 16.dp)) {
                    SourceHomeActiveFilters(mapOf("Generi" to listOf("Avventura", "Fantasy"))) {}
                }
            }
        }
    }
}

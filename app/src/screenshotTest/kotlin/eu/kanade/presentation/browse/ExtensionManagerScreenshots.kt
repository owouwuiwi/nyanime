package eu.kanade.presentation.browse

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import eu.kanade.domain.extension.ExtensionDistribution
import eu.kanade.domain.extension.ExtensionHomeSupport
import eu.kanade.domain.extension.ExtensionPackageMetadata
import eu.kanade.domain.extension.ExtensionUpdateStatus
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.presentation.browse.components.ExtensionEntry
import eu.kanade.presentation.browse.components.ExtensionIntegrationDetails
import eu.kanade.presentation.browse.components.ExtensionManagerContent
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.extension.InstallStep

@PreviewTest
@Preview(name = "ExtensionManager", widthDp = 393, heightDp = 850, locale = "it")
@Preview(name = "ExtensionManagerNarrow", widthDp = 320, heightDp = 850, fontScale = 1.5f, locale = "it")
@Composable
fun ExtensionManagerScreenshot() = ExtensionsPreview()

@PreviewTest
@Preview(name = "ExtensionManagerLegacy", widthDp = 393, heightDp = 850, locale = "it")
@Composable
fun ExtensionManagerLegacyScreenshot() = ExtensionsPreview(modern = false)

@PreviewTest
@Preview(name = "ExtensionCatalogue", widthDp = 393, heightDp = 850, locale = "it")
@Composable
fun ExtensionCatalogueScreenshot() = ExtensionsPreview(catalogue = true)

@PreviewTest
@Preview(name = "ExtensionDetailsNarrow", widthDp = 320, heightDp = 850, fontScale = 1.5f, locale = "it")
@Composable
fun ExtensionDetailsScreenshot() = TachiyomiPreviewTheme {
    Surface(Modifier.fillMaxSize()) {
        ExtensionIntegrationDetails(
            ExtensionPackageMetadata(home = ExtensionHomeSupport.NONE),
            ExtensionUpdateStatus.DIFFERENT_DISTRIBUTION,
            "Example catalogue",
            isRin = true,
        )
    }
}

@Composable
private fun ExtensionsPreview(modern: Boolean = true, catalogue: Boolean = false) = TachiyomiPreviewTheme(
    appTheme = if (modern) AppTheme.NYANIME else AppTheme.DEFAULT,
    modernUi = modern,
    darkTheme = modern,
) {
    val entries = listOf(
        previewEntry(
            "local",
            "Catalogo illustrato: una raccolta con un nome molto lungo",
            ExtensionHomeSupport.READY,
            ExtensionUpdateStatus.MANUAL,
        ).copy(
            metadata = ExtensionPackageMetadata(
                home = ExtensionHomeSupport.READY,
                distribution = ExtensionDistribution(id = "local", label = "Edizione locale"),
            ),
        ),
        previewEntry("ordinary", "Biblioteca illustrata", ExtensionHomeSupport.NONE, ExtensionUpdateStatus.CURRENT),
        previewEntry(
            "update",
            "Catalogo internazionale",
            ExtensionHomeSupport.PARTIAL,
            ExtensionUpdateStatus.AVAILABLE,
        ).copy(step = InstallStep.Downloading),
    )
    Surface(Modifier.fillMaxSize()) {
        ExtensionManagerContent(
            entries.map {
                if (catalogue) {
                    it.copy(
                        installed = false,
                        metadata = ExtensionPackageMetadata(),
                        status = ExtensionUpdateStatus.UNVERIFIED,
                        step = InstallStep.Idle,
                    )
                } else {
                    it
                }
            },
            PaddingValues(),
            1,
            false,
            {},
            initialCatalogue = catalogue,
        )
    }
}

private fun previewEntry(
    id: String,
    name: String,
    home: ExtensionHomeSupport,
    status: ExtensionUpdateStatus,
) = ExtensionEntry(
    id = id, name = name, version = "1.4.12", installed = true,
    languages = setOf(
        "it",
    ),
    repository = "Example catalogue",
    metadata = ExtensionPackageMetadata(home = home), status = status,
    icon = { Icon(Icons.Outlined.Extension, null) }, onOpen = {}, onAction = {}, onCancel = {}, onLongClick = {},
)

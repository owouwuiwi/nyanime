package eu.kanade.presentation.more

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.data.updater.UpdateScreenPhase

@PreviewTest
@Preview(name = "UpdateAvailableDark", widthDp = 393, heightDp = 852, locale = "it")
@Preview(name = "UpdateAvailableNarrow", widthDp = 320, heightDp = 720, fontScale = 1.5f, locale = "it")
@Composable
fun AppUpdateAvailableScreenshot() = UpdatePreview(UpdateScreenPhase.AVAILABLE, true)

@PreviewTest
@Preview(name = "UpdateDownloadLight", widthDp = 393, heightDp = 852, locale = "en-rUS")
@Preview(name = "UpdateDownloadNarrow", widthDp = 320, heightDp = 720, fontScale = 1.5f, locale = "en-rUS")
@Composable
fun AppUpdateDownloadScreenshot() = UpdatePreview(UpdateScreenPhase.DOWNLOADING, false)

@PreviewTest
@Preview(name = "UpdateReadyDark", widthDp = 393, heightDp = 852, locale = "it")
@Composable
fun AppUpdateReadyScreenshot() = UpdatePreview(UpdateScreenPhase.READY, true)

@PreviewTest
@Preview(name = "UpdateErrorLight", widthDp = 320, heightDp = 720, fontScale = 1.3f, locale = "en-rUS")
@Composable
fun AppUpdateErrorScreenshot() = UpdatePreview(UpdateScreenPhase.FAILED, false)

@Composable
private fun UpdatePreview(phase: UpdateScreenPhase, dark: Boolean) {
    TachiyomiPreviewTheme(darkTheme = dark) {
        NewUpdateScreen(
            versionName = "0.42.0.0",
            installedVersion = "0.41.0.2",
            changelogInfo = "<!-- nyanime-changelog:1 -->\n" +
                if (dark) {
                    "### 0.42.0.0: Aggiornamenti più leggibili\n#### Novità\n" +
                        "- Tutte le versioni da recuperare, con note più leggibili.\n" +
                        "- Storico completo disponibile anche offline.\n#### Prestazioni\n" +
                        "- Liste virtualizzate.\n### 0.41.0.7: Sync\n#### Correzioni\n- Ripristino dopo timeout."
                } else {
                    "### 0.42.0.0: Clearer updates\n#### Features\n" +
                        "- Every release you missed, with clearer notes.\n" +
                        "- The complete history works offline.\n#### Performance\n" +
                        "- Virtualized lists.\n### 0.41.0.7: Sync\n#### Fixes\n- Recovery after timeouts."
                },
            phase = phase,
            downloadProgress = 64,
            onCancelDownload = {},
            onOpenInBrowser = {},
            onRejectUpdate = {},
            onAcceptUpdate = {},
            onOpenHistory = {},
        )
    }
}

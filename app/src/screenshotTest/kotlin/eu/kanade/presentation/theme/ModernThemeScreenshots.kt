package eu.kanade.presentation.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.discovery.MangaHomeScreenshot
import eu.kanade.presentation.discovery.NyanimeHomeScreenshot
import eu.kanade.presentation.discovery.NyanimeRemoteScreenshot
import eu.kanade.presentation.more.settings.widget.PreferenceGroupHeader
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.motion.PosterReadyDetailScreenshot
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.stringResource

@PreviewTest
@Preview(name = "ThemeChoiceLight", widthDp = 393, heightDp = 852, locale = "it")
@Preview(name = "ThemeChoiceNarrow", widthDp = 320, heightDp = 720, fontScale = 1.5f, locale = "it")
@Composable
fun ModernThemeChoiceLight() {
    TachiyomiPreviewTheme(darkTheme = false) {
        ThemeChoiceScreen(ThemeMode.LIGHT, false, false, {}, {})
    }
}

@PreviewTest
@Preview(name = "ThemeChoiceDark", widthDp = 393, heightDp = 852, locale = "it")
@Composable
fun ModernThemeChoiceDark() {
    TachiyomiPreviewTheme(darkTheme = true) {
        ThemeChoiceScreen(ThemeMode.DARK, false, false, {}, {})
    }
}

@PreviewTest
@Preview(name = "ThemeChoiceSystemError", widthDp = 320, heightDp = 720, fontScale = 1.3f, locale = "it")
@Composable
fun ModernThemeChoiceSystem() {
    TachiyomiPreviewTheme(darkTheme = false) {
        ThemeChoiceScreen(ThemeMode.SYSTEM, false, true, {}, {})
    }
}

@PreviewTest
@Preview(name = "ModernAppearanceLight", widthDp = 393, heightDp = 852, locale = "it")
@Preview(
    name = "ModernAppearanceDark",
    widthDp = 393,
    heightDp = 852,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
    locale = "it",
)
@Composable
fun ModernAppearancePreview() {
    TachiyomiPreviewTheme {
        Scaffold(topBar = {
            AppBar(title = stringResource(MR.strings.pref_category_appearance), navigateUp = {})
        }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                item { PreferenceGroupHeader(stringResource(AYMR.strings.nyanime_appearance_title)) }
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        ThemeModeCards(ThemeMode.SYSTEM, {})
                    }
                }
                item {
                    SwitchPreferenceWidget(
                        title = stringResource(MR.strings.pref_dark_theme_pure_black),
                        subtitle = stringResource(AYMR.strings.nyanime_amoled_description),
                        onCheckedChanged = {},
                    )
                }
                item {
                    SwitchPreferenceWidget(
                        title = stringResource(AYMR.strings.nyanime_source_logo_title),
                        subtitle = stringResource(AYMR.strings.nyanime_source_logo_description),
                        checked = true,
                        onCheckedChanged = {},
                    )
                }
            }
        }
    }
}

@PreviewTest
@Preview(name = "ModernHomeLight", widthDp = 393, heightDp = 1100, locale = "it")
@Preview(name = "ModernHomeNarrow", widthDp = 320, heightDp = 1100, fontScale = 1.4f, locale = "it")
@Preview(
    name = "ModernHomeDark",
    widthDp = 393,
    heightDp = 1100,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
    locale = "it",
)
@Composable
fun ModernHomeThemePreview() = NyanimeHomeScreenshot()

@PreviewTest
@Preview(name = "ModernDetailsLight", widthDp = 393, heightDp = 852, locale = "it")
@Preview(name = "ModernDetailsNarrow", widthDp = 320, heightDp = 852, fontScale = 1.4f, locale = "it")
@Composable
fun ModernDetailsThemePreview() = PosterReadyDetailScreenshot()

@PreviewTest
@Preview(name = "ModernMangaLight", widthDp = 393, heightDp = 1050, locale = "it")
@Composable
fun ModernMangaThemePreview() = MangaHomeScreenshot()

@PreviewTest
@Preview(name = "ModernRemoteLight", widthDp = 393, heightDp = 852, locale = "it")
@Composable
fun ModernRemoteThemePreview() = NyanimeRemoteScreenshot()

package eu.kanade.tachiyomi.ui.cloud

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import nyanime.telegram.TelegramState

@PreviewTest
@Preview(name = "CloudPhoneDark", widthDp = 393, heightDp = 850, locale = "it")
@Preview(name = "CloudPhoneNarrow", widthDp = 320, heightDp = 850, fontScale = 1.5f, locale = "it")
@Composable
fun CloudPhoneDarkScreenshot() = PhonePreview(dark = true)

@PreviewTest
@Preview(name = "CloudPhoneLight", widthDp = 393, heightDp = 850, locale = "it")
@Composable
fun CloudPhoneLightScreenshot() = PhonePreview(dark = false)

@Composable
private fun PhonePreview(dark: Boolean) = TachiyomiPreviewTheme(darkTheme = dark) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
            LoginCard(TelegramState(authorization = "authorizationStateWaitPhoneNumber"), false, null, {
            }, {}, {}, {}, {})
        }
    }
}

package eu.kanade.presentation.discovery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import java.io.File

/** Static endpoints use the same renderer without depending on asynchronous preview requests. */
@PreviewTest
@Preview(name = "BrandingEndpoints", widthDp = 320, heightDp = 240, locale = "it")
@Composable
fun SourceHomeBrandingEndpointsScreenshot() {
    TachiyomiPreviewTheme(appTheme = AppTheme.NYANIME) {
        Surface {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Box(Modifier.fillMaxWidth().height(48.dp)) { SourceHomeWordmarkTransition(null, false) }
                listOf("light", "dark", "icon").forEachIndexed { index, kind ->
                    val painter = remember(kind) {
                        val file = System.getenv("NYANIME_PREVIEW_BRANDING")?.let { File(it, "$kind.png") }
                        val bitmap = file?.takeIf { it.isFile }?.inputStream()?.use(BitmapFactory::decodeStream)
                            ?: Bitmap.createBitmap(if (kind == "icon") 100 else 500, 100, Bitmap.Config.ARGB_8888)
                        BitmapPainter(bitmap.asImageBitmap())
                    }
                    Box(Modifier.fillMaxWidth().height(48.dp)) {
                        SourceHomeWordmarkTransition(
                            SourceHomeLoadedLogo(
                                SourceHomeLogo(
                                    index + 1L,
                                    "Fonte",
                                    "https://example.test/$kind.png",
                                    name = if (kind == "icon") "Cinema" else null,
                                    background = kind.takeUnless { it == "icon" },
                                ),
                                painter,
                            ),
                            false,
                        )
                    }
                }
            }
        }
    }
}

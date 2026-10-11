package eu.kanade.tachiyomi.ui

import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import eu.kanade.presentation.theme.colorscheme.NyanimeColorScheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class NyanimeLightPaletteTest {
    @Test
    fun readableForegroundsKeepContrastOnLightSurfaces() {
        val scheme = NyanimeColorScheme.lightScheme
        for ((text, surface) in listOf(
            scheme.onBackground to scheme.background,
            scheme.onSurface to scheme.surface,
            scheme.onSurfaceVariant to scheme.surfaceContainerHigh,
            scheme.onPrimary to scheme.primary,
            scheme.onPrimaryContainer to scheme.primaryContainer,
            scheme.onSecondary to scheme.secondary,
            scheme.onTertiary to scheme.tertiary,
            scheme.onError to scheme.error,
        )) {
            val lighter = maxOf(text.luminance(), surface.luminance())
            val darker = minOf(text.luminance(), surface.luminance())
            assertTrue((lighter + 0.05f) / (darker + 0.05f) >= 4.5f, "Insufficient text contrast: $text / $surface")
        }
    }

    @Test
    fun nativeAndComposeColorsStayConsistent() {
        val path = listOf(
            File("src/main/res/values/colors_nyanime.xml"),
            File("app/src/main/res/values/colors_nyanime.xml"),
        )
            .first { it.exists() }
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(path).getElementsByTagName("color")
        val native = (0 until nodes.length).associate { index ->
            val node = nodes.item(index)
            node.attributes.getNamedItem("name").nodeValue to node.textContent.removePrefix("#").toLong(16)
        }
        val scheme = NyanimeColorScheme.lightScheme
        for ((name, color) in mapOf(
            "primary" to scheme.primary,
            "onPrimary" to scheme.onPrimary,
            "background" to scheme.background,
            "onBackground" to scheme.onBackground,
            "surface" to scheme.surface,
            "onSurface" to scheme.onSurface,
            "surfaceContainerHigh" to scheme.surfaceContainerHigh,
            "outlineVariant" to scheme.outlineVariant,
        )) {
            assertEquals(color.toArgb().toLong() and 0xffffff, native.getValue("nyanime_$name"))
        }
    }
}

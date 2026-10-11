package eu.kanade.tachiyomi.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import eu.kanade.presentation.theme.colorscheme.NyanimeClassicColorScheme
import eu.kanade.presentation.theme.colorscheme.NyanimeColorScheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.pow

class NyanimeIdentityPaletteTest {
    @Test
    fun bothIdentitiesKeepReadableTextAndControlsInBothModes() {
        for (palette in listOf(NyanimeColorScheme, NyanimeClassicColorScheme)) {
            for (dark in listOf(false, true)) {
                for (amoled in listOf(false, true)) {
                    val scheme = palette.getColorScheme(dark, amoled)
                    for ((foreground, background) in listOf(
                        scheme.onPrimary to scheme.primary,
                        scheme.onPrimaryContainer to scheme.primaryContainer,
                        scheme.onSecondaryContainer to scheme.secondaryContainer,
                        scheme.onSurface to scheme.surface,
                        scheme.onSurfaceVariant to scheme.surfaceVariant,
                    )) {
                        val ratio = contrast(foreground, background)
                        assertTrue(ratio >= 4.5, "Text contrast $ratio is below 4.5:1; dark=$dark amoled=$amoled")
                    }
                }
            }
        }
    }

    @Test
    fun nativeWidgetsAndComposeShareTheSameBrandRoles() {
        for ((prefix, palette) in listOf(
            "nyanime_" to NyanimeColorScheme,
            "nyanime_classic_" to NyanimeClassicColorScheme,
        )) {
            for (dark in listOf(false, true)) {
                val folder = if (dark) "values-night" else "values"
                val file = resources.resolve("$folder/colors_${prefix.removeSuffix("_")}.xml")
                val document = xmlFactory().newDocumentBuilder().parse(file)
                val colors = document.getElementsByTagName("color")
                val actual = (0 until colors.length).associate { index ->
                    val node = colors.item(index)
                    node.attributes.getNamedItem("name").nodeValue to parseColor(node.textContent)
                }
                roles(palette.getColorScheme(dark, false)).forEach { (role, color) ->
                    assertEquals(color.toArgb(), actual[prefix + role], "$folder $prefix$role")
                }
            }
        }
    }

    @Test
    fun amoledChangesSurfacesWithoutLosingTheSelectedIdentity() {
        for (palette in listOf(NyanimeColorScheme, NyanimeClassicColorScheme)) {
            assertEquals(Color.Black, palette.getColorScheme(true, true).background)
            assertEquals(palette.darkScheme.primary, palette.getColorScheme(true, true).primary)
            assertEquals(palette.lightScheme, palette.getColorScheme(false, true))
        }
    }

    private fun roles(s: ColorScheme) = mapOf(
        "primary" to s.primary, "onPrimary" to s.onPrimary,
        "primaryContainer" to s.primaryContainer, "onPrimaryContainer" to s.onPrimaryContainer,
        "background" to s.background, "onBackground" to s.onBackground,
        "surface" to s.surface, "onSurface" to s.onSurface,
        "surfaceVariant" to s.surfaceVariant, "onSurfaceVariant" to s.onSurfaceVariant,
        "secondaryContainer" to s.secondaryContainer, "onSecondaryContainer" to s.onSecondaryContainer,
    )

    private fun contrast(a: Color, b: Color): Double {
        fun luminance(c: Color): Double {
            fun linear(v: Float): Double = if (v <= .04045f) v / 12.92 else ((v + .055) / 1.055).pow(2.4)
            return .2126 * linear(c.red) + .7152 * linear(c.green) + .0722 * linear(c.blue)
        }
        val (low, high) = listOf(luminance(a), luminance(b)).sorted()
        return (high + .05) / (low + .05)
    }

    private fun parseColor(raw: String): Int {
        val hex = raw.trim().removePrefix("#")
        return (if (hex.length == 6) "FF$hex" else hex).toLong(16).toInt()
    }

    companion object {
        internal val resources: File get() {
            val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
            return generateSequence(workingDirectory) { it.parentFile }
                .map { it.resolve("app/src/main/res") }.first { it.isDirectory }
        }

        internal fun xmlFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
    }
}

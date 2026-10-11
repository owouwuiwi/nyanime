package eu.kanade.presentation.theme

import android.content.Context
import androidx.compose.material3.ColorScheme
import eu.kanade.presentation.theme.colorscheme.BaseColorScheme
import eu.kanade.presentation.theme.colorscheme.CloudflareColorScheme
import eu.kanade.presentation.theme.colorscheme.CottoncandyColorScheme
import eu.kanade.presentation.theme.colorscheme.DoomColorScheme
import eu.kanade.presentation.theme.colorscheme.GreenAppleColorScheme
import eu.kanade.presentation.theme.colorscheme.LavenderColorScheme
import eu.kanade.presentation.theme.colorscheme.MatrixColorScheme
import eu.kanade.presentation.theme.colorscheme.MidnightDuskColorScheme
import eu.kanade.presentation.theme.colorscheme.MochaColorScheme
import eu.kanade.presentation.theme.colorscheme.MonetColorScheme
import eu.kanade.presentation.theme.colorscheme.MonochromeColorScheme
import eu.kanade.presentation.theme.colorscheme.NordColorScheme
import eu.kanade.presentation.theme.colorscheme.NyanimeClassicColorScheme
import eu.kanade.presentation.theme.colorscheme.SapphireColorScheme
import eu.kanade.presentation.theme.colorscheme.StrawberryColorScheme
import eu.kanade.presentation.theme.colorscheme.TachiyomiColorScheme
import eu.kanade.presentation.theme.colorscheme.TakoColorScheme
import eu.kanade.presentation.theme.colorscheme.TealTurqoiseColorScheme
import eu.kanade.presentation.theme.colorscheme.TidalWaveColorScheme
import eu.kanade.presentation.theme.colorscheme.YinYangColorScheme
import eu.kanade.presentation.theme.colorscheme.YotsubaColorScheme

enum class NyanimePalette {
    NYANIME,
    DEFAULT,
    MONET,
    CLOUDFLARE,
    COTTONCANDY,
    DOOM,
    GREEN_APPLE,
    LAVENDER,
    MATRIX,
    MIDNIGHT_DUSK,
    MONOCHROME,
    MOCHA,
    SAPPHIRE,
    NORD,
    STRAWBERRY_DAIQUIRI,
    TAKO,
    TEALTURQUOISE,
    TIDAL_WAVE,
    YINYANG,
    YOTSUBA,
    ;

    fun colorScheme(context: Context, dark: Boolean, amoled: Boolean): ColorScheme {
        val palette: BaseColorScheme = when (this) {
            NYANIME -> NyanimeClassicColorScheme
            DEFAULT -> TachiyomiColorScheme
            MONET -> MonetColorScheme(context)
            CLOUDFLARE -> CloudflareColorScheme
            COTTONCANDY -> CottoncandyColorScheme
            DOOM -> DoomColorScheme
            GREEN_APPLE -> GreenAppleColorScheme
            LAVENDER -> LavenderColorScheme
            MATRIX -> MatrixColorScheme
            MIDNIGHT_DUSK -> MidnightDuskColorScheme
            MONOCHROME -> MonochromeColorScheme
            MOCHA -> MochaColorScheme
            SAPPHIRE -> SapphireColorScheme
            NORD -> NordColorScheme
            STRAWBERRY_DAIQUIRI -> StrawberryColorScheme
            TAKO -> TakoColorScheme
            TEALTURQUOISE -> TealTurqoiseColorScheme
            TIDAL_WAVE -> TidalWaveColorScheme
            YINYANG -> YinYangColorScheme
            YOTSUBA -> YotsubaColorScheme
        }
        return palette.getColorScheme(dark, amoled)
    }
}

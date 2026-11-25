package eu.kanade.presentation.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import eu.kanade.domain.ui.NyanimeIdentity
import eu.kanade.domain.ui.ThemeSettingsRepository
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.domain.ui.resolveDarkTheme
import eu.kanade.presentation.motion.ProvideMotionPreferences
import eu.kanade.presentation.util.rememberSafeUriHandler
import eu.kanade.tachiyomi.ui.base.delegate.ThemingDelegateImpl
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

val LocalNyanimeIdentity = staticCompositionLocalOf { NyanimeIdentity.CLASSIC }

/** Compatibility wrapper for existing manga screens; the app now has one visual theme. */
@Composable
fun LegacyMangaTheme(content: @Composable () -> Unit) {
    content()
}

@Composable
fun MangaSectionTheme(legacy: Boolean, content: @Composable () -> Unit) {
    content()
}

@Composable
fun TachiyomiTheme(
    appTheme: AppTheme? = null,
    amoled: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val uiPreferences = Injekt.get<UiPreferences>()
    val repository = remember { Injekt.get<ThemeSettingsRepository>() }
    val settings by repository.settings.collectAsState(repository.current())
    val isAmoled by uiPreferences.themeDarkAmoled().collectAsState()
    val activity = LocalContext.current as? Activity
    LaunchedEffect(activity, settings.identity, isAmoled, settings.mode) {
        activity?.let { ThemingDelegateImpl().applyAppTheme(it) }
    }
    AppThemeContent(
        appTheme = appTheme ?: uiPreferences.activeAppTheme(),
        isAmoled = amoled ?: isAmoled,
        modernUi = appTheme?.let { it == AppTheme.NYANIME } ?: true,
        darkTheme = resolveDarkTheme(settings.mode, isSystemInDarkTheme()),
        identity = settings.identity,
        content = { ProvideMotionPreferences(content) },
    )
}

@Composable
fun TachiyomiPreviewTheme(
    appTheme: AppTheme = AppTheme.NYANIME,
    isAmoled: Boolean = false,
    modernUi: Boolean = appTheme == AppTheme.NYANIME,
    darkTheme: Boolean = isSystemInDarkTheme(),
    identity: NyanimeIdentity = NyanimeIdentity.CLASSIC,
    content: @Composable () -> Unit,
) = AppThemeContent(appTheme, isAmoled, modernUi, darkTheme, identity, content)

@Composable
private fun AppThemeContent(
    appTheme: AppTheme,
    isAmoled: Boolean,
    modernUi: Boolean,
    darkTheme: Boolean,
    identity: NyanimeIdentity,
    content: @Composable () -> Unit,
) {
    val uriHandler = rememberSafeUriHandler()
    CompositionLocalProvider(
        LocalNyanimeIdentity provides identity,
        LocalUriHandler provides uriHandler,
    ) {
        NyanimeTheme(
            palette = appTheme.toPalette(),
            dark = darkTheme,
            amoled = isAmoled,
            modern = modernUi,
            content = content,
        )
    }
}

private fun AppTheme.toPalette(): NyanimePalette = when (this) {
    AppTheme.NYANIME -> NyanimePalette.NYANIME
    AppTheme.DEFAULT -> NyanimePalette.DEFAULT
    AppTheme.MONET -> NyanimePalette.MONET
    AppTheme.CLOUDFLARE -> NyanimePalette.CLOUDFLARE
    AppTheme.COTTONCANDY -> NyanimePalette.COTTONCANDY
    AppTheme.DOOM -> NyanimePalette.DOOM
    AppTheme.GREEN_APPLE -> NyanimePalette.GREEN_APPLE
    AppTheme.LAVENDER -> NyanimePalette.LAVENDER
    AppTheme.MATRIX -> NyanimePalette.MATRIX
    AppTheme.MIDNIGHT_DUSK -> NyanimePalette.MIDNIGHT_DUSK
    AppTheme.MONOCHROME -> NyanimePalette.MONOCHROME
    AppTheme.MOCHA -> NyanimePalette.MOCHA
    AppTheme.SAPPHIRE -> NyanimePalette.SAPPHIRE
    AppTheme.NORD -> NyanimePalette.NORD
    AppTheme.STRAWBERRY_DAIQUIRI -> NyanimePalette.STRAWBERRY_DAIQUIRI
    AppTheme.TAKO -> NyanimePalette.TAKO
    AppTheme.TEALTURQUOISE -> NyanimePalette.TEALTURQUOISE
    AppTheme.TIDAL_WAVE -> NyanimePalette.TIDAL_WAVE
    AppTheme.YINYANG -> NyanimePalette.YINYANG
    AppTheme.YOTSUBA -> NyanimePalette.YOTSUBA
    AppTheme.DARK_BLUE, AppTheme.HOT_PINK, AppTheme.BLUE -> NyanimePalette.DEFAULT
}

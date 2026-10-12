package eu.kanade.tachiyomi.ui.base.delegate

import android.app.Activity
import android.content.res.Configuration
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

interface ThemingDelegate {
    fun applyAppTheme(activity: Activity)

    companion object {
        fun getThemeResIds(appTheme: AppTheme, isAmoled: Boolean): List<Int> {
            return buildList(2) {
                add(themeResources.getOrDefault(appTheme, R.style.Theme_Tachiyomi))
                if (isAmoled) add(R.style.ThemeOverlay_Tachiyomi_Amoled)
            }
        }
    }
}

class ThemingDelegateImpl : ThemingDelegate {
    override fun applyAppTheme(activity: Activity) {
        val uiPreferences = Injekt.get<UiPreferences>()
        ThemingDelegate.getThemeResIds(
            if (activity is ReaderActivity) uiPreferences.legacyMangaTheme().get() else uiPreferences.activeAppTheme(),
            uiPreferences.themeDarkAmoled().get() &&
                activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES,
        )
            .forEach(activity::setTheme)
    }
}

private val themeResources: Map<AppTheme, Int> = mapOf(
    AppTheme.NYANIME to R.style.Theme_Tachiyomi_Nyanime,
    AppTheme.MONET to R.style.Theme_Tachiyomi_Monet,
    AppTheme.COTTONCANDY to R.style.Theme_Tachiyomi_CottonCandy,
    AppTheme.GREEN_APPLE to R.style.Theme_Tachiyomi_GreenApple,
    AppTheme.LAVENDER to R.style.Theme_Tachiyomi_Lavender,
    AppTheme.MIDNIGHT_DUSK to R.style.Theme_Tachiyomi_MidnightDusk,
    AppTheme.MONOCHROME to R.style.Theme_Tachiyomi_Monochrome,
    AppTheme.MOCHA to R.style.Theme_Tachiyomi_Mocha,
    AppTheme.NORD to R.style.Theme_Tachiyomi_Nord,
    AppTheme.STRAWBERRY_DAIQUIRI to R.style.Theme_Tachiyomi_StrawberryDaiquiri,
    AppTheme.TAKO to R.style.Theme_Tachiyomi_Tako,
    AppTheme.TEALTURQUOISE to R.style.Theme_Tachiyomi_TealTurquoise,
    AppTheme.YINYANG to R.style.Theme_Tachiyomi_YinYang,
    AppTheme.YOTSUBA to R.style.Theme_Tachiyomi_Yotsuba,
    AppTheme.CLOUDFLARE to R.style.Theme_Tachiyomi_Cloudflare,
    AppTheme.SAPPHIRE to R.style.Theme_Tachiyomi_Sapphire,
    AppTheme.DOOM to R.style.Theme_Tachiyomi_Doom,
    AppTheme.MATRIX to R.style.Theme_Tachiyomi_Matrix,
    AppTheme.TIDAL_WAVE to R.style.Theme_Tachiyomi_TidalWave,
)

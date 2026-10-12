package eu.kanade.domain.ui

import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.domain.ui.model.NavStyle
import eu.kanade.domain.ui.model.StartScreen
import eu.kanade.domain.ui.model.TabletUiMode
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.isDynamicColorAvailable
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class UiPreferences(
    private val preferenceStore: PreferenceStore,
) {

    fun themeMode() = preferenceStore.getEnum(ThemePreferenceKeys.MODE, ThemeMode.SYSTEM)

    fun appTheme() = preferenceStore.getEnum(
        "pref_app_theme",
        AppTheme.NYANIME,
    )

    fun themeDarkAmoled() = preferenceStore.getBoolean("pref_theme_dark_amoled_key", false)

    fun sourceHomeLogo() = preferenceStore.getBoolean("source_home_logo", false)

    fun homeCategoryOrder() = preferenceStore.getString("nyanime_home_category_order", "[]")

    fun showMangaInOtherLanguages() = preferenceStore.getBoolean("nyanime_manga_home_other_languages", true)

    fun preferredMangaHomeSource() = preferenceStore.getLong("nyanime_manga_home_preferred_source", 0L)

    fun inAppUpdateInstallation() = preferenceStore.getBoolean("nyanime_in_app_update_installation", true)

    fun dismissedReadyUpdate() = preferenceStore.getString("nyanime_dismissed_ready_update")

    fun dismissedLibraryUpdates() = preferenceStore.getStringSet("nyanime_dismissed_library_updates")

    fun viewedLibraryShelfNotices() = preferenceStore.getStringSet("nyanime_viewed_library_shelf_notices")

    fun autoAcknowledgeHomeUpdates() = preferenceStore.getBoolean("nyanime_auto_acknowledge_home_updates", true)

    fun lastSeenAnimeUpdateNotice() = preferenceStore.getLong("nyanime_last_seen_anime_update_notice")

    fun lastSeenMangaUpdateNotice() = preferenceStore.getLong("nyanime_last_seen_manga_update_notice")

    fun modernUi() = preferenceStore.getBoolean("nyanime_modern_ui", true)

    fun legacyAppTheme() = preferenceStore.getEnum("nyanime_legacy_app_theme", legacyMangaTheme().get())

    fun activeAppTheme() = AppTheme.NYANIME

    private val legacyDefaultTheme get() = if (DeviceUtil.isDynamicColorAvailable) AppTheme.MONET else AppTheme.DEFAULT

    fun legacyMangaTheme() = preferenceStore.getEnum("nyanime_legacy_manga_theme", legacyDefaultTheme)

    fun installNyanimeThemeOnce() {
        val installed = preferenceStore.getBoolean("nyanime_visual_identity_v1", false)
        if (installed.get()) return
        legacyMangaTheme().set(appTheme().get().takeUnless { it == AppTheme.NYANIME } ?: legacyDefaultTheme)
        legacyAppTheme().set(legacyMangaTheme().get())
        appTheme().set(AppTheme.NYANIME)
        installed.set(true)
    }

    fun relativeTime() = preferenceStore.getBoolean("relative_time_v2", true)

    fun dateFormat() = preferenceStore.getString("app_date_format", "")

    fun tabletUiMode() = preferenceStore.getEnum("tablet_ui_mode", TabletUiMode.AUTOMATIC)

    fun startScreen() = preferenceStore.getEnum("start_screen", StartScreen.HOME)

    fun navStyle() = preferenceStore.getEnum("bottom_rail_nav_style", NavStyle.DISCOVERY)

    fun installDiscoveryNavigationOnce() {
        val migrated = preferenceStore.getBoolean("fork_discovery_navigation_v1", false)
        if (migrated.get()) return
        startScreen().set(StartScreen.HOME)
        navStyle().set(NavStyle.DISCOVERY)
        migrated.set(true)
    }

    fun installModernNavigationOnce() {
        installDiscoveryNavigationOnce()
        val migrated = preferenceStore.getBoolean("nyanime_modern_navigation_v2", false)
        if (migrated.get()) return
        modernUi().set(true)
        appTheme().set(AppTheme.NYANIME)
        navStyle().set(NavStyle.DISCOVERY)
        migrated.set(true)
    }

    companion object {
        fun dateFormat(format: String): DateTimeFormatter = when (format) {
            "" -> DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
            else -> DateTimeFormatter.ofPattern(format, Locale.getDefault())
        }
    }
}

package eu.kanade.tachiyomi.data.theme

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import eu.kanade.domain.ui.NyanimeIdentity
import eu.kanade.domain.ui.ThemeSettingsRepository
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.domain.ui.resolveDarkTheme
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal fun systemDarkTheme(): Boolean =
    Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

internal enum class LauncherAppearance { CLASSIC_RED, CLASSIC_ORANGE }

internal fun launcherAppearance(identity: NyanimeIdentity, mode: ThemeMode, systemDark: Boolean): LauncherAppearance =
    when {
        resolveDarkTheme(mode, systemDark) -> LauncherAppearance.CLASSIC_RED
        else -> LauncherAppearance.CLASSIC_ORANGE
    }

class LauncherIconController(
    context: Context,
    private val systemDark: () -> Boolean = ::systemDarkTheme,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val identity: () -> NyanimeIdentity = { Injekt.get<ThemeSettingsRepository>().current().identity },
) {
    private val packageManager = context.packageManager

    // Keep the old default component identity: upgrades must not add another default-enabled icon.
    private val legacy = ComponentName(context.packageName, "eu.kanade.tachiyomi.ui.main.LauncherRed")
    private val red = ComponentName(context.packageName, "eu.kanade.tachiyomi.ui.main.LauncherClassicRed")
    private val orange = ComponentName(context.packageName, "eu.kanade.tachiyomi.ui.main.LauncherOrange")
    private val aliases = listOf(legacy, red, orange)

    fun reconcile(mode: ThemeMode) {
        val selected = when (launcherAppearance(identity(), mode, systemDark())) {
            LauncherAppearance.CLASSIC_RED -> red
            LauncherAppearance.CLASSIC_ORANGE -> orange
        }
        val previous = aliases.filter { it != selected }
        try {
            if (isEnabled(selected) && previous.none(::isEnabled)) return
            if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.setComponentEnabledSettings(
                    (listOf(selected) + previous).map { component ->
                        PackageManager.ComponentEnabledSetting(
                            component,
                            if (component == selected) {
                                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            } else {
                                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                            },
                            PackageManager.DONT_KILL_APP,
                        )
                    },
                )
            } else {
                // Never leave the package without an enabled launcher entry.
                setEnabled(selected, true)
                previous.forEach { setEnabled(it, false) }
            }
        } catch (error: Exception) {
            logcat(LogPriority.WARN, error) { "Unable to update launcher appearance" }
        }
    }

    private fun isEnabled(component: ComponentName): Boolean =
        when (packageManager.getComponentEnabledSetting(component)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> component == legacy
            else -> false
        }

    private fun setEnabled(component: ComponentName, enabled: Boolean) {
        packageManager.setComponentEnabledSetting(
            component,
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            PackageManager.DONT_KILL_APP,
        )
    }
}

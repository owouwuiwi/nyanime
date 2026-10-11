package eu.kanade.tachiyomi.data.theme

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.domain.ui.resolveDarkTheme
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

internal fun systemDarkTheme(): Boolean =
    Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

class LauncherIconController(
    context: Context,
    private val systemDark: () -> Boolean = ::systemDarkTheme,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {
    private val packageManager = context.packageManager
    private val red = ComponentName(context.packageName, "eu.kanade.tachiyomi.ui.main.LauncherRed")
    private val orange = ComponentName(context.packageName, "eu.kanade.tachiyomi.ui.main.LauncherOrange")

    fun reconcile(mode: ThemeMode) {
        val selected = if (resolveDarkTheme(mode, systemDark())) red else orange
        val previous = if (selected == red) orange else red
        try {
            if (isEnabled(selected) && !isEnabled(previous)) return
            if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.setComponentEnabledSettings(
                    listOf(
                        PackageManager.ComponentEnabledSetting(
                            selected,
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            PackageManager.DONT_KILL_APP,
                        ),
                        PackageManager.ComponentEnabledSetting(
                            previous,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP,
                        ),
                    ),
                )
            } else {
                // Never leave the package without an enabled launcher entry.
                setEnabled(selected, true)
                setEnabled(previous, false)
            }
        } catch (error: Exception) {
            logcat(LogPriority.WARN, error) { "Unable to update launcher appearance" }
        }
    }

    private fun isEnabled(component: ComponentName): Boolean =
        when (packageManager.getComponentEnabledSetting(component)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> component == red
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

package eu.kanade.tachiyomi.data.theme

import android.app.UiModeManager
import android.content.Context
import android.net.Uri
import android.os.Build
import eu.kanade.domain.ui.ThemeModeApplier
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.domain.ui.model.setAppCompatDelegateThemeMode
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/** Registers the app's appearance with Android before its next system-rendered splash. */
class AndroidThemeModeApplier internal constructor(
    private val uiModeManager: UiModeManager?,
    private val updateLauncherIcon: (ThemeMode) -> Unit,
    private val sdkInt: Int,
    private val applyCompatMode: (ThemeMode) -> Unit = ::setAppCompatDelegateThemeMode,
    private val notifyModeChanged: () -> Unit = {},
) : ThemeModeApplier {
    private var compatFallbackActive = false

    constructor(context: Context) : this(
        uiModeManager = context.getSystemService(UiModeManager::class.java),
        updateLauncherIcon = LauncherIconController(context)::reconcile,
        sdkInt = Build.VERSION.SDK_INT,
        notifyModeChanged = {
            context.contentResolver.notifyChange(Uri.parse("content://${context.packageName}.appearance"), null)
            context.contentResolver.notifyChange(Uri.parse("content://${context.packageName}.addons.appearance"), null)
        },
    )

    override fun apply(mode: ThemeMode) {
        if (sdkInt >= Build.VERSION_CODES.S && uiModeManager != null) {
            try {
                uiModeManager.setApplicationNightMode(
                    when (mode) {
                        ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
                        ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
                        // AUTO clears the package override; Android follows its current system mode.
                        ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                    },
                )
                if (compatFallbackActive) {
                    applyCompatMode(ThemeMode.SYSTEM)
                    compatFallbackActive = false
                }
            } catch (error: Exception) {
                logcat(LogPriority.WARN, error) { "Unable to register application night mode" }
                compatFallbackActive = true
                applyCompatMode(mode)
            }
        } else {
            applyCompatMode(mode)
        }
        reconcile(mode)
        notifyModeChanged()
    }

    override fun reconcile(mode: ThemeMode) = updateLauncherIcon(mode)
}

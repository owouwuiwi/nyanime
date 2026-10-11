package eu.kanade.tachiyomi.crash

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.kanade.presentation.crash.CrashScreen
import eu.kanade.presentation.theme.LocalDarkTheme
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.view.setComposeContent

class CrashActivity : BaseActivity() {
    private val model by viewModels<CrashReportViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        val exception = GlobalExceptionHandler.getThrowableFromIntent(intent)
        setComposeContent {
            val darkTheme = LocalDarkTheme.current
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            val report by model.report.state.collectAsStateWithLifecycle()
            LaunchedEffect(report) {
                val ready = report as? CrashReportState.Ready ?: return@LaunchedEffect
                if (isFinishing || isDestroyed) return@LaunchedEffect
                try {
                    startActivity(
                        ready.file.getUriCompat(this@CrashActivity).toShareIntent(this@CrashActivity, "text/plain"),
                    )
                    model.report.consumed(ready.file)
                } catch (_: Exception) {
                    model.report.shareFailed(ready.file)
                }
            }
            CrashScreen(
                exception = exception,
                preparingReport = report == CrashReportState.Preparing || report is CrashReportState.Ready,
                reportFailed = report == CrashReportState.Failed,
                onShareClick = { model.report.prepare(exception) },
                onRestartClick = {
                    finishAffinity()
                    startActivity(
                        Intent(this@CrashActivity, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        },
                    )
                },
                onCloseClick = { finishAffinity() },
            )
        }
    }
}

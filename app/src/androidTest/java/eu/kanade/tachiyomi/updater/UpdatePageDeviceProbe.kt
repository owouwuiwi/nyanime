package eu.kanade.tachiyomi.updater

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock

/** Opt-in native UI probe, compiled only into the temporary instrumentation APK. */
class UpdatePageDeviceProbe : Instrumentation() {
    private var options = Bundle()

    override fun onCreate(arguments: Bundle?) {
        options = arguments ?: Bundle()
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            // The target owns Compose calls, so R8 rewrites their references consistently.
            val host = Class.forName("eu.kanade.tachiyomi.updater.UpdatePageProbeHost", true, targetContext.classLoader)
            val url = checkNotNull(options.getString("url"))
            if (options.getString("cleanup") == "true") {
                host.getMethod("cleanup", Context::class.java, String::class.java).invoke(null, targetContext, url)
                result.putString("stream", "Only this probe's download files removed.")
                finish(Activity.RESULT_OK, result)
                return
            }
            val activity = startActivitySync(
                Intent().setClassName(targetContext.packageName, "eu.kanade.tachiyomi.ui.main.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            SystemClock.sleep(1500)
            runOnMainSync {
                host.getMethod(
                    "show",
                    Activity::class.java,
                    String::class.java,
                    String::class.java,
                    Boolean::class.javaPrimitiveType,
                )
                    .invoke(
                        null,
                        activity,
                        options.getString("version") ?: "0.99.0.0",
                        url,
                        options.getString("theme") != "light",
                    )
            }
            SystemClock.sleep((options.getString("duration")?.toLongOrNull() ?: 90000).coerceIn(1000, 300000))
            result.putString("stream", "Native update page probe finished; inspect device evidence separately.")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", android.util.Log.getStackTraceString(error))
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}

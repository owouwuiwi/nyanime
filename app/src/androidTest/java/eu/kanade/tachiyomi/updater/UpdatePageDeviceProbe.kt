package eu.kanade.tachiyomi.updater

import android.app.Activity
import android.app.Instrumentation
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.io.File
import java.util.concurrent.TimeUnit

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
            // The production mode uses the final APK's mapping; no host hook is shipped or installed.
            val host = if (options.containsKey("screenClass")) {
                null
            } else {
                Class.forName("eu.kanade.tachiyomi.updater.UpdatePageProbeHost", true, targetContext.classLoader)
            }
            if (options.getString("regression") == "true") {
                checkDownloadPage(host)
                result.putString(
                    "stream",
                    "Update remains open during download and after failure regardless of the retired installation preference. Original preference restored.",
                )
                finish(Activity.RESULT_OK, result)
                return
            }
            checkNotNull(host)
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
            onMain {
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

    private fun checkDownloadPage(host: Class<*>?) {
        val activity = startActivitySync(
            Intent().setClassName(targetContext.packageName, "eu.kanade.tachiyomi.ui.main.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        SystemClock.sleep(1500)
        val preference = host?.getMethod("legacyInstallationPreference", Boolean::class.javaPrimitiveType)
        val shared = targetContext.getSharedPreferences(
            "${targetContext.packageName}_preferences",
            Context.MODE_PRIVATE,
        )
        val key = "nyanime_in_app_update_installation"
        fun automaticInstallation(enabled: Boolean): Boolean = if (preference != null) {
            preference.invoke(null, enabled) as Boolean
        } else {
            shared.getBoolean(key, true).also { check(shared.edit().putBoolean(key, enabled).commit()) }
        }
        val journal = File(targetContext.cacheDir, "update-probe-preference.txt")
        if (host == null && journal.isFile) {
            val saved = journal.readText().split(':')
            check(saved.size == 2 && saved.all { it == "true" || it == "false" })
            onMain {
                val edit = shared.edit()
                if (saved[0].toBoolean()) edit.putBoolean(key, saved[1].toBoolean()) else edit.remove(key)
                check(edit.commit())
            }
            journal.delete()
        }
        val present = shared.contains(key)
        var original = shared.getBoolean(key, true)
        if (host == null) journal.writeText("$present:$original")
        onMain { original = automaticInstallation(false) }
        try {
            for (automatic in listOf(false, true)) {
                MockWebServer().use { server ->
                    server.enqueue(MockResponse.Builder().code(404).headersDelay(2, TimeUnit.SECONDS).build())
                    server.start()
                    if (host == null) {
                        // Real navigation creates a parent, so closing the page would fail the test.
                        fun depth(): Int {
                            var result = 0
                            onMain {
                                val field = activity.javaClass.getDeclaredField(
                                    checkNotNull(options.getString("navigatorField")),
                                )
                                field.isAccessible = true
                                val navigator = checkNotNull(field.get(activity))
                                result = navigator.javaClass.getMethod("getSize").invoke(navigator) as Int
                            }
                            return result
                        }
                        repeat(2) {
                            if (depth() < 2) {
                                clickText(targetText("label_more"))
                                SystemClock.sleep(500)
                            }
                        }
                        check(depth() >= 2) { "A real parent screen is required to detect premature dismissal" }
                    }
                    onMain {
                        automaticInstallation(automatic)
                        if (host != null) {
                            host.getMethod(
                                "show",
                                Activity::class.java,
                                String::class.java,
                                String::class.java,
                                Boolean::class.javaPrimitiveType,
                            )
                                .invoke(null, activity, "0.99.0.0", server.url("/update.apk").toString(), true)
                        } else {
                            val field = activity.javaClass.getDeclaredField(
                                checkNotNull(options.getString("navigatorField")),
                            )
                            field.isAccessible = true
                            val navigator = checkNotNull(field.get(activity))
                            val screenClass = Class.forName(
                                checkNotNull(options.getString("screenClass")),
                                true,
                                targetContext.classLoader,
                            )
                            val constructor = screenClass.getDeclaredConstructor(
                                String::class.java,
                                String::class.java,
                                String::class.java,
                                String::class.java,
                            )
                            constructor.isAccessible = true
                            val screen = constructor.newInstance(
                                "0.99.0.0",
                                "#### Correzioni\n- Prova controllata del download.",
                                "https://example.invalid/release",
                                server.url("/update.apk").toString(),
                            )
                            val push = navigator.javaClass.methods.firstOrNull {
                                if (it.name != options.getString("pushMethod") ||
                                    it.parameterCount != 1
                                ) {
                                    return@firstOrNull false
                                }
                                val type = it.parameterTypes.single()
                                type.isAssignableFrom(screenClass) ||
                                    List::class.java.isAssignableFrom(type) ||
                                    (type.isArray && type.componentType?.isAssignableFrom(screenClass) == true)
                            } ?: error(
                                "No compatible push ${options.getString(
                                    "pushMethod",
                                )} on ${navigator.javaClass.name}: " +
                                    navigator.javaClass.methods.joinToString {
                                        "${it.name}(${it.parameterTypes.joinToString { type -> type.name }})"
                                    },
                            )
                            push.isAccessible = true
                            val type = push.parameterTypes.single()
                            val argument = when {
                                type.isArray -> java.lang.reflect.Array.newInstance(screenClass, 1).also {
                                    java.lang.reflect.Array.set(it, 0, screen)
                                }
                                List::class.java.isAssignableFrom(type) -> listOf(screen)
                                else -> screen
                            }
                            push.invoke(navigator, argument)
                        }
                    }
                    clickText(targetText("app_update_update"))
                    SystemClock.sleep(300)
                    assertVisible(host)
                    check(server.takeRequest(15, TimeUnit.SECONDS) != null) { "Download was not started" }
                    waitForText(targetText("app_update_retry"))
                    assertVisible(host)
                    check(server.requestCount == 1) { "A single click started multiple downloads" }
                    if (host != null) {
                        host.getMethod("cleanup", Context::class.java, String::class.java)
                            .invoke(null, targetContext, server.url("/update.apk").toString())
                    } else {
                        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    }
                }
            }
        } finally {
            onMain {
                automaticInstallation(original)
                if (host == null && !present) check(shared.edit().remove(key).commit())
            }
            if (host == null) journal.delete()
            options.getString("notificationId")?.toIntOrNull()?.let {
                targetContext.getSystemService(NotificationManager::class.java).cancel(it)
            }
        }
    }

    private fun assertVisible(host: Class<*>?) {
        if (host == null) {
            waitForText(targetText("app_update_available_heading"))
            return
        }
        var visible = false
        onMain { visible = host.getMethod("updateVisible").invoke(null) as Boolean }
        check(visible) { "Update page closed after starting the download" }
    }

    private fun onMain(action: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync {
            try {
                action()
            } catch (error: Throwable) {
                failure = error
            }
        }
        failure?.let { throw it }
    }

    private fun targetText(name: String): String {
        val id = targetContext.resources.getIdentifier(name, "string", targetContext.packageName)
        check(id != 0) { "Missing update resource: $name" }
        return targetContext.getString(id)
    }

    private fun clickText(text: String) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < deadline) {
            val matches = mutableListOf<Rect>()
            fun collect(node: AccessibilityNodeInfo?) {
                if (node == null) return
                if (node.isVisibleToUser &&
                    (node.text?.toString() == text || node.contentDescription?.toString() == text)
                ) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    if (!bounds.isEmpty) matches += bounds
                }
                for (index in 0 until node.childCount) collect(node.getChild(index))
            }
            collect(uiAutomation.rootInActiveWindow)
            val target = matches.maxByOrNull { it.centerY() }
            if (target != null) {
                val now = SystemClock.uptimeMillis()
                sendPointerSync(
                    MotionEvent.obtain(
                        now,
                        now,
                        MotionEvent.ACTION_DOWN,
                        target.centerX().toFloat(),
                        target.centerY().toFloat(),
                        0,
                    ),
                )
                sendPointerSync(
                    MotionEvent.obtain(
                        now,
                        now + 75,
                        MotionEvent.ACTION_UP,
                        target.centerX().toFloat(),
                        target.centerY().toFloat(),
                        0,
                    ),
                )
                return
            }
            SystemClock.sleep(100)
        }
        error("Expected touch target is not visible: $text")
    }

    private fun waitForText(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 15000
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null ||
                node.text?.toString() == text ||
                node.contentDescription?.toString() == text
            ) {
                return node
            }
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        while (SystemClock.elapsedRealtime() < deadline) {
            find(uiAutomation.rootInActiveWindow)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Expected update action is not visible: $text")
    }
}

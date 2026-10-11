package nyanime.secretgame

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.WebView
import android.widget.FrameLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Base64

/** Self-contained test APK. Never reads the host application's files or library. */
class SecretGameProbeActivity : Activity() {
    lateinit var container: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = FrameLayout(this)
        setContentView(container)
    }
}

class SecretGameDeviceProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val report = Bundle()
        var result = Activity.RESULT_OK
        var activity: SecretGameProbeActivity? = null
        var engine: SandspielEngine? = null
        try {
            activity = startActivitySync(
                Intent(targetContext, SecretGameProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as SecretGameProbeActivity
            val host = activity
            val file = File(targetContext.filesDir, "secret-game/sandspiel.scene")
            check(!file.exists()) { "Use a clean, isolated test APK installation." }
            runBlocking {
                suspend fun launch(): Pair<SandspielEngine, WebView> = withContext(Dispatchers.Main) {
                    val view = WebView(host)
                    host.container.addView(view, FrameLayout.LayoutParams(-1, -1))
                    val next = SandspielEngine(targetContext)
                    engine = next
                    next.attach(view)
                    next to view
                }
                suspend fun ready(next: SandspielEngine) {
                    val state = withTimeout(20_000) { next.state.first { !it.loading || it.failure != null } }
                    check(state.failure == null) {
                        "Offline startup failed: ${state.failure}; inspect SecretGame logcat."
                    }
                    check(state.paused)
                }
                val (first, view) = launch()
                ready(first)
                withContext(Dispatchers.Main) {
                    check(view.settings.blockNetworkLoads && !view.settings.allowFileAccess)
                    first.command("material", 3)
                    first.command("brush", 4)
                }
                withTimeout(3_000) { first.state.first { it.material == 3 && it.brush == 4 } }
                val point = withContext(Dispatchers.Main) {
                    val position = IntArray(2)
                    view.getLocationOnScreen(position)
                    (position[0] + view.width / 2f) to (position[1] + view.height / 2f)
                }
                suspend fun drag() {
                    val down = SystemClock.uptimeMillis()
                    for (step in 0..12) {
                        val action = when (step) {
                            0 -> MotionEvent.ACTION_DOWN
                            12 -> MotionEvent.ACTION_UP
                            else -> MotionEvent.ACTION_MOVE
                        }
                        val event = MotionEvent.obtain(
                            down,
                            SystemClock.uptimeMillis(),
                            action,
                            point.first + (step - 6) * 8f,
                            point.second + (step - 6) * 4f,
                            0,
                        )
                        event.source = InputDevice.SOURCE_TOUCHSCREEN
                        sendPointerSync(event)
                        event.recycle()
                        delay(20)
                    }
                }
                withContext(Dispatchers.Main) { first.command("resume") }
                withTimeout(3_000) { first.state.first { !it.paused } }
                // Touch movement reaches fluid input code that a startup/tap-only probe misses.
                repeat(4) { drag() }
                delay(1_000)
                check(first.state.value.failure == null) { "Running drag failed: ${first.state.value.failure}" }
                withContext(Dispatchers.Main) {
                    first.foreground(false)
                    first.foreground(true)
                }
                drag()
                delay(500)
                check(first.state.value.failure == null) { "Drag after foreground failed." }
                withContext(Dispatchers.Main) { first.command("pause") }
                withTimeout(3_000) { first.state.first { it.paused } }
                waitForIdleSync()
                check(withContext(Dispatchers.Main) { first.checkpoint() }) { "Snapshot acknowledgement failed." }
                val saved = requireNotNull(SceneStore(file).read())
                fun cellBytes(scene: String): ByteArray = Base64.getDecoder().decode(
                    Json.parseToJsonElement(scene).jsonObject.getValue("cells").jsonPrimitive.content,
                )
                val before = cellBytes(saved)
                check((before.indices step 4).any { before[it].toInt() != 0 }) { "Touch drags did not paint cells." }
                withContext(Dispatchers.Main) { first.close() }
                val (second, _) = launch()
                ready(second)
                check(second.state.value.material == 3 && second.state.value.brush == 4)
                check(withContext(Dispatchers.Main) { second.checkpoint() })
                val restored = cellBytes(requireNotNull(SceneStore(file).read()))
                for (index in before.indices step 4) {
                    check((0..2).all { before[index + it] == restored[index + it] }) {
                        "Restore changed the composition at cell ${index / 4}."
                    }
                    check(restored[index + 3] == 0.toByte()) { "Restore retained a transient generation stamp." }
                }
                report.putString(
                    "stream",
                    "PASS: packaged CSP/WASM/WebGL, running touch drags, foreground, native bridge, save/restore paused.\n",
                )
            }
        } catch (error: Throwable) {
            result = Activity.RESULT_CANCELED
            report.putString("stream", error.stackTraceToString())
        } finally {
            runOnMainSync {
                engine?.close()
                activity?.finish()
            }
            File(targetContext.filesDir, "secret-game/sandspiel.scene").delete()
            finish(result, report)
        }
    }
}

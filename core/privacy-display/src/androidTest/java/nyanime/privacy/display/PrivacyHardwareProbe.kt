package nyanime.privacy.display

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Color
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/** Manual physical-device probe: correlate each case with vendor logs and side-angle observation. */
class PrivacyHardwareProbe : Instrumentation() {
    private var margins: List<Int>? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        margins = arguments?.getString("margins")?.split(',')?.map { it.toInt().also { n -> require(n in 0..256) } }
        start()
    }

    override fun onStart() {
        val results = Bundle()
        var activity: PrivacyProbeActivity? = null
        var session: PrivacyDisplaySession<AndroidPrivacyDisplayTarget>? = null
        var target: AndroidPrivacyDisplayTarget? = null
        try {
            val available = AndroidPrivacyDisplayBackends.create(
                PrivacyDisplayDevice(Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT),
            )
            check(available.capability == PrivacyDisplayCapability.Available)
            val backend = RawProbeBackend()
            activity = startActivitySync(
                Intent(targetContext, PrivacyProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as PrivacyProbeActivity
            waitForIdleSync()
            val owner = activity
            val dimensions = Point()
            runOnMainSync {
                @Suppress("DEPRECATION")
                owner.window.decorView.display.getRealSize(dimensions)
                target = AndroidPrivacyDisplayTarget(owner.window.decorView as ViewGroup)
                session = PrivacyDisplaySession(target, backend) { Log.i(TAG, "STATE $it") }
            }
            val panel = PrivacyBounds(0, 0, dimensions.x, dimensions.y)
            val center = PrivacyBounds(panel.right / 4, panel.bottom / 3, panel.right * 3 / 4, panel.bottom * 2 / 3)
            val inset = PrivacyBounds(24, 24, panel.right - 24, panel.bottom - 24)
            val cases = margins?.map { margin ->
                "margin-$margin" to PrivacyRegion(
                    PrivacyBounds(margin, margin, panel.right - margin, panel.bottom - margin),
                    0,
                )
            } ?: listOf(
                "center-square" to PrivacyRegion(center, 0, 0f),
                "center-rounded" to PrivacyRegion(center, 0, 45f),
                "panel-square" to PrivacyRegion(panel, 0, 0f),
                "panel-rounded" to PrivacyRegion(panel, 0, 45f),
                "inset-square" to PrivacyRegion(inset, 0, 0f),
                "inset-rounded" to PrivacyRegion(inset, 0, 45f),
            )
            for ((name, region) in cases) {
                runOnMainSync {
                    session?.update(null, false)
                    owner.label.text =
                        "Nyanime — prova hardware\n$name\n${region.bounds}\nraggio ${region.cornerRadiusPx} px"
                    Log.i(TAG, "CASE $name $region")
                    session?.update(region, true)
                }
                waitForIdleSync()
                SystemClock.sleep(2500)
            }
            results.putString("stream", "Probe completed. API acceptance is not optical verification.\n")
        } catch (failure: Exception) {
            results.putString("stream", "Probe failed: $failure\n")
            Log.e(TAG, "Probe failed", failure)
        } finally {
            runOnMainSync {
                session?.close()
                target?.close()
                activity?.finish()
            }
            finish(Activity.RESULT_OK, results)
        }
    }

    companion object {
        private const val TAG = "NyanimePrivacyProbe"
    }
}

/** Use unfitted geometry in this test window to measure the firmware's actual limits. */
private class RawProbeBackend : PrivacyDisplayBackend<AndroidPrivacyDisplayTarget> {
    override val capability = PrivacyDisplayCapability.Available
    private val api = SamsungPrivacyMethods.resolve(View::class.java)

    override fun apply(
        target: AndroidPrivacyDisplayTarget,
        region: PrivacyRegion,
        previous: PrivacyRegion?,
    ): Result<PrivacyRegion?> = samsungApiCall {
        val placement = target.place(region, PrivacyPanelExpansion(0, 0, 0, 0), 0) ?: return@samsungApiCall null
        val view = checkNotNull(target.currentView)
        api.apply(view, placement.displayRegion.copy(bounds = placement.localBounds), previous == null)
        view.invalidate()
        placement.displayRegion
    }

    override fun clear(target: AndroidPrivacyDisplayTarget): Result<Unit> = samsungApiCall {
        try {
            target.currentView?.let(api::clear)
        } finally {
            target.detach()
        }
    }
}

class PrivacyProbeActivity : Activity() {
    lateinit var label: TextView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        label = TextView(this).apply {
            setBackgroundColor(Color.WHITE)
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            textSize = 24f
            text = "Nyanime — prova hardware"
        }
        setContentView(label)
    }
}

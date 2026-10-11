package eu.kanade.tachiyomi.data.download.anime.ultra

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import tachiyomi.domain.download.service.DownloadPreferences
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

internal class UltraProcessingControl(
    private val context: Context,
    private val preferences: DownloadPreferences,
    cooling: Boolean,
) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val policy = UltraProcessingPolicy(cooling)
    private val stopped = AtomicBoolean(false)

    @Volatile private var interactiveOrWarm = false

    @Volatile var waitingReason: String? = null
        private set

    fun sample(): String? {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            ?.takeIf { it > 0 }?.div(10f)
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val status = if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else 0
        val now = SystemClock.elapsedRealtime()
        val headroom = if (Build.VERSION.SDK_INT >= 30) {
            thermalHeadroom.sample(now) { power.getThermalHeadroom(0) }
        } else {
            null
        }
        interactiveOrWarm = power.isInteractive ||
            power.isPowerSaveMode ||
            status >= 1 ||
            (temperature ?: 0f) >= 37f ||
            (headroom ?: 0f) >= 0.8f
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        waitingReason = policy.waitingReason(
            UltraProcessingPolicy.Environment(
                interactive = power.isInteractive,
                screenOffOnly = preferences.ultraOnlyWhileScreenOff().get(),
                playerActive = UltraPlaybackGuard.playerActive,
                charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
                chargingOnly = preferences.ultraOnlyWhileCharging().get(),
                batteryPercent = if (scale > 0 && level >= 0) level * 100 / scale else null,
                batteryCelsius = temperature,
                thermalStatus = status,
                thermalHeadroom = headroom,
                powerSave = power.isPowerSaveMode,
                lowMemory = memory.lowMemory,
            ),
        )
        return waitingReason
    }

    fun stop() {
        stopped.set(true)
    }

    fun begin() {
        stopped.set(false)
        checkRunning()
    }

    fun checkRunning() {
        check(!stopped.get() && !UltraPlaybackGuard.playerActive && waitingReason == null) {
            "Elaborazione Ultra sospesa"
        }
    }

    /** Called only on the export GL thread, after glFinish has drained this strip. */
    fun rest(workNanos: Long) {
        val duration = UltraProcessingPolicy.restNanos(workNanos, interactiveOrWarm)
        val start = System.nanoTime()
        while (System.nanoTime() - start < duration) {
            checkRunning()
            LockSupport.parkNanos(minOf(20_000_000, duration - (System.nanoTime() - start)))
        }
        checkRunning()
    }

    companion object {
        // Android limits this sensor across callers, including enqueue and successive workers.
        private val thermalHeadroom = UltraThermalHeadroomCache()
    }
}

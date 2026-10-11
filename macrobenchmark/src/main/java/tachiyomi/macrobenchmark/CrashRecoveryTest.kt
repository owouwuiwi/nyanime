package tachiyomi.macrobenchmark

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.Test
import java.util.regex.Pattern

/** Opens the real error-process Activity in an isolated application; never induces a fatal crash. */
class CrashRecoveryTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val restart = By.text(Pattern.compile("Reopen Nyanime|Riapri Nyanime"))
    private val share = By.text(Pattern.compile("Share report|Condividi rapporto"))
    private val details = By.text(Pattern.compile("Error details|Dettagli dell’errore"))

    private fun open(extra: String = "") {
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        device.executeShellCommand(
            "am start -W -S -n $TARGET_PACKAGE/$FIXTURE_ACTIVITY --ez crash_recovery true $extra",
        )
        check(device.waitForFresh(restart, 20_000))
        check(device.waitForFresh(share, 5_000))
    }

    @Test fun errorDetailsStartCollapsedAndExpandInTheRealRecoveryProcess() {
        open()
        try {
            check(!device.hasObject(By.textContains("Synthetic recovery check")))
            clickFresh(device, details)
            check(device.waitForFresh(By.textContains("Synthetic recovery check"), 5_000))
            check(device.executeShellCommand("dumpsys activity activities").contains(".crash.CrashActivity"))
            clickFresh(device, details)
            check(device.waitForFresh(By.textContains("Synthetic recovery check"), 5_000, exists = false))
        } finally {
            device.pressBack()
        }
    }

    @Test fun missingDetailsRemainUsableInLightTheme() {
        open("--ez empty_details true --ez light_theme true")
        try {
            clickFresh(device, details)
            val missing = Pattern.compile(
                "Error details are unavailable.*|I dettagli dell’errore non sono disponibili.*",
            )
            check(device.waitForFresh(By.text(missing), 5_000))
            check(device.hasObject(restart))
            check(device.hasObject(share))
        } finally {
            device.pressBack()
        }
    }

    @Test fun reportExportOpensTheAndroidChooserWithoutSendingAnything() {
        open()
        try {
            clickFresh(device, share)
            val deadline = SystemClock.uptimeMillis() + 15_000
            var chooser = false
            while (SystemClock.uptimeMillis() < deadline) {
                chooser = device.executeShellCommand("dumpsys activity activities").contains("ChooserActivity")
                if (chooser) break
                SystemClock.sleep(100)
            }
            check(chooser) { "Crash report did not reach the system share chooser" }
            val report = device.executeShellCommand(
                "cat /sdcard/Android/data/$TARGET_PACKAGE/cache/nyanime_crash_logs.txt",
            )
            check(report.contains("Synthetic recovery check"))
            check(report.contains("App version:"))
        } finally {
            device.pressBack()
            device.pressBack()
        }
    }
}

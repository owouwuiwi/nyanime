package tachiyomi.macrobenchmark

import android.annotation.SuppressLint
import androidx.benchmark.Outputs
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.Test

class AccessibilitySmokeTest {
    @Test fun sourceArtworkRecoversAutomaticallyManuallyAndAfterHomeRefresh() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        device.executeShellCommand("am start -W -n $TARGET_PACKAGE/$FIXTURE_ACTIVITY --ez artwork true")
        try {
            check(device.waitForFresh(By.text("ARTWORK_RESPONSE=ready"), 15_000))
            check(device.waitForFresh(By.text("ARTWORK_REQUESTS=2"), 5_000))
            device.waitForIdle()
            capture(device, "artwork-automatic-recovery")

            clickFresh(device, By.text("Caso manuale"))
            check(device.waitForFresh(By.text("ARTWORK_SCENARIO=manuale"), 5_000))
            check(device.waitForFresh(By.text("ARTWORK_REQUESTS=2"), 15_000))
            check(device.waitForFresh(By.desc("Ricarica immagine"), 5_000))
            device.waitForIdle()
            capture(device, "artwork-retry-visible")
            clickFresh(device, By.desc("Ricarica immagine"))
            check(device.waitForFresh(By.text("ARTWORK_RESPONSE=ready"), 5_000))
            check(device.waitForFresh(By.text("ARTWORK_REQUESTS=3"), 5_000))
            capture(device, "artwork-manual-recovery")

            clickFresh(device, By.text("Caso refresh"))
            check(device.waitForFresh(By.text("ARTWORK_SCENARIO=refresh"), 5_000))
            check(device.waitForFresh(By.text("ARTWORK_RESPONSE=failed"), 5_000))
            check(device.waitForFresh(By.text("ARTWORK_REQUESTS=2"), 15_000))
            check(device.waitForFresh(By.desc("Ricarica immagine"), 5_000))
            clickFresh(device, By.text("Aggiorna Home"))
            check(device.waitForFresh(By.text("ARTWORK_RESPONSE=ready"), 5_000))
            check(device.waitForFresh(By.text("ARTWORK_REQUESTS=3"), 5_000))
            capture(device, "artwork-refresh-recovery")
        } finally {
            device.pressBack()
        }
    }

    @Test fun largeTextLandscapeAndTablet() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        prepareFixtures(device)
        val originalScale = device.executeShellCommand("settings get system font_scale").trim()
            .toFloatOrNull() ?: 1f
        val originalSize = displayOverride(device, "size")
        val originalDensity = displayOverride(device, "density")
        try {
            device.executeShellCommand("settings put system font_scale 1.8")
            launchHome(device)
            capture(device, "home-large-text")
            openTab(device, "library_anime")
            capture(device, "anime-library-large-text")
            openTab(device, "library_manga")
            capture(device, "manga-library-large-text")
            device.setOrientationLeft()
            device.waitForIdle()
            openTab(device, "discovery")
            capture(device, "home-landscape-large-text")
            device.setOrientationNatural()
            device.executeShellCommand("wm size 1600x2560")
            device.executeShellCommand("wm density 320")
            launchHome(device)
            capture(device, "home-tablet-large-text")
            check(device.hasObject(By.res("library_anime")))
            check(device.hasObject(By.res("library_manga")))
            check(device.hasObject(By.res("browse")))
            check(device.hasObject(By.res("more")))
            check(device.findObject(By.res("library_manga")).visibleBounds.centerX() < device.displayWidth / 4) {
                "Tablet configuration did not activate the navigation rail"
            }
            device.executeShellCommand("am start -W -n $TARGET_PACKAGE/$FIXTURE_ACTIVITY --ez reader true")
            check(device.waitForFresh(By.res(TARGET_PACKAGE, "reader_pager"), 15_000))
            device.waitForIdle()
            capture(device, "reader-tablet-large-text")
            openReaderSettings(device)
            capture(device, "reader-settings-reading-large-text")
            selectReaderSettingsPage(device, "General", 1)
            capture(device, "reader-settings-general-large-text")
            selectReaderSettingsPage(device, "Custom filter", 2)
            capture(device, "reader-settings-filter-large-text")
        } finally {
            device.executeShellCommand("wm size $originalSize")
            device.executeShellCommand("wm density $originalDensity")
            device.executeShellCommand("settings put system font_scale $originalScale")
            device.setOrientationNatural()
            device.unfreezeRotation()
        }
    }

    private fun launchHome(device: UiDevice) {
        device.executeShellCommand("am force-stop $TARGET_PACKAGE")
        device.executeShellCommand("am start -W -n $TARGET_PACKAGE/eu.kanade.tachiyomi.ui.main.MainActivity")
        awaitTabContent(device, "discovery")
    }

    private fun displayOverride(device: UiDevice, property: String): String =
        device.executeShellCommand("wm $property").lineSequence()
            .firstOrNull { it.startsWith("Override") }?.substringAfter(":")?.trim() ?: "reset"

    private fun openReaderSettings(device: UiDevice) {
        if (!device.hasObject(By.res("reader_settings_button"))) {
            clickFresh(device, By.res(TARGET_PACKAGE, "reader_pager"))
        }
        clickFresh(device, By.res("reader_settings_button"))
        if (!device.waitForFresh(By.res("reader_settings_page_0"), 10_000)) {
            failJourney(device, "Reading mode settings did not open")
        }
        device.waitForIdle()
    }

    private fun selectReaderSettingsPage(device: UiDevice, title: String, page: Int) {
        clickFresh(device, By.text(title))
        if (!device.waitForFresh(By.res("reader_settings_page_$page"), 10_000)) {
            failJourney(device, "Reader settings page did not open: $title")
        }
        for (previous in (0..2).filter { it != page }) {
            if (!device.waitForFresh(By.res("reader_settings_page_$previous"), 10_000, exists = false)) {
                failJourney(device, "Previous reader settings page remained visible: $previous")
            }
        }
        device.waitForIdle()
    }

    @SuppressLint("RestrictedApi")
    private fun capture(device: UiDevice, name: String) {
        refreshAccessibilityCache()
        Outputs.writeFile("$name.png") { check(device.takeScreenshot(it)) }
        Outputs.writeFile("$name.xml") { device.dumpWindowHierarchy(it) }
    }
}

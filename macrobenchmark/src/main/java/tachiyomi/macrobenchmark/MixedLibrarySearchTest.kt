package tachiyomi.macrobenchmark

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.Test

class MixedLibrarySearchTest {
    @Test fun searchFindsAnimeAndManga() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        prepareFixtures(device)
        startMainActivity(device)
        openTab(device, "libraries")
        clickFresh(device, By.desc("Visualizzazione").hasAncestor(By.res("content_libraries")))
        clickFresh(device, By.text("Scaffale"))
        librarySearch(device)
        // The IME can leave only the first result visible on small screens.
        if (device.executeShellCommand("dumpsys input_method").contains("mInputShown=true")) {
            device.pressBack()
        }

        val title = By.textStartsWith("Benchmark 0020")
        for (medium in listOf("ANIME", "MANGA")) {
            val row = By.clickable(true).hasDescendant(title).hasDescendant(By.text(medium))
            if (!device.waitForFresh(row, 15_000)) {
                failJourney(device, "Missing filtered $medium row for Benchmark 0020")
            }
        }
    }
}

package tachiyomi.macrobenchmark

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.Test

class DetailsLoadingTest {
    @Test fun animeAndMangaKeepTheirHeroGeometryWhenTheirModelsResolve() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        for (manga in listOf(false, true)) {
            device.executeShellCommand(
                "am start -W -S -n $TARGET_PACKAGE/$FIXTURE_ACTIVITY --ez detail_loading true --ez manga $manga",
            )
            try {
                val title = By.text("A long example title for a stable detail page")
                check(device.waitForFresh(title, 20_000))
                device.waitForIdle()
                refreshAccessibilityCache()
                val before = device.findObjects(title).maxBy { it.visibleBounds.height() }.visibleBounds
                clickFresh(device, By.text("Resolve details"))
                check(device.waitForFresh(By.text("DETAIL_READY"), 5_000))
                SystemClock.sleep(400)
                device.waitForIdle()
                refreshAccessibilityCache()
                val after = device.findObjects(title).maxBy { it.visibleBounds.height() }.visibleBounds
                check(before == after) {
                    "Hero title moved from $before to $after when loading finished (manga=$manga)"
                }
                check(device.hasObject(By.text("Resume part 1")))
            } finally {
                device.pressBack()
            }
        }
    }
}

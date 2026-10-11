package tachiyomi.macrobenchmark

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.Test
import java.util.regex.Pattern

class ArtworkFluidityTest {
    @Test fun homeRefreshRecoversFailedArtworkWithoutChangingTheEntry() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        device.executeShellCommand("am start -W -S -n $TARGET_PACKAGE/$FIXTURE_ACTIVITY --ez artwork_fluidity true")
        try {
            check(device.waitForFresh(By.text("ARTWORK_PRESENTED=1"), 15_000))
            clickFresh(device, By.text("Fail next image"))
            val retry = By.desc(Pattern.compile("Reload image|Ricarica immagine"))
            check(device.waitForFresh(retry, 5_000))
            clickFresh(device, By.text("Recover failed image"))
            check(device.waitForFresh(By.text("ARTWORK_PRESENTED=2"), 5_000))
            device.waitForIdle()
            refreshAccessibilityCache()
            check(device.hasObject(By.text("ARTWORK_TRANSFERS=3")))
            check(!device.hasObject(retry))
        } finally {
            device.pressBack()
        }
    }

    @Test fun refreshingHealthyArtworkDoesNotRestartItsRequestEvenAfterCacheEviction() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        device.executeShellCommand("am start -W -S -n $TARGET_PACKAGE/$FIXTURE_ACTIVITY --ez artwork_fluidity true")
        try {
            check(device.waitForFresh(By.text("ARTWORK_PRESENTED=1"), 15_000))
            repeat(3) { index ->
                clickFresh(device, By.text("Refresh loaded image"))
                check(device.waitForFresh(By.text("ARTWORK_REFRESH=${index + 1}"), 5_000))
                device.waitForIdle()
                refreshAccessibilityCache()
                check(device.hasObject(By.text("ARTWORK_TRANSFERS=1")))
                check(device.hasObject(By.text("ARTWORK_PRESENTED=1")))
            }
        } finally {
            device.pressBack()
        }
    }

    @Test fun metadataKeepsTheImageAndMemoryReturnNotifiesOnce() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        device.executeShellCommand("am start -W -S -n $TARGET_PACKAGE/$FIXTURE_ACTIVITY --ez artwork_fluidity true")
        try {
            check(device.waitForFresh(By.text("ARTWORK_PRESENTED=1"), 15_000))
            check(device.hasObject(By.text("ARTWORK_TRANSFERS=1")))

            clickFresh(device, By.text("Change metadata"))
            check(device.waitForFresh(By.text("ARTWORK_REVISION=1"), 5_000))
            device.waitForIdle()
            check(device.hasObject(By.text("ARTWORK_PRESENTED=1")))
            check(device.hasObject(By.text("ARTWORK_TRANSFERS=1")))

            clickFresh(device, By.text("Restore cached image"))
            check(device.waitForFresh(By.text("ARTWORK_PRESENTED=2"), 5_000))
            device.waitForIdle()
            refreshAccessibilityCache()
            check(device.hasObject(By.text("ARTWORK_PRESENTED=2")))
            check(device.hasObject(By.text("ARTWORK_TRANSFERS=1")))
        } finally {
            device.pressBack()
        }
    }
}

package tachiyomi.macrobenchmark

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.benchmark.Outputs
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

const val TARGET_PACKAGE = "xyz.jmir.tachiyomi.mi.anime4k.benchmark"
const val FIXTURE_ACTIVITY = "eu.kanade.tachiyomi.benchmark.BenchmarkSetupActivity"

// Interaction timings exclude activity launch. Recreate the content activity in its warm process
// and let openTab await the actual visible content rather than a forwarding launcher window.
fun MacrobenchmarkScope.startMainActivity() {
    device.executeShellCommand(
        "am start -W -f 0x10008000 -n $TARGET_PACKAGE/eu.kanade.tachiyomi.ui.main.MainActivity",
    )
}

fun refreshAccessibilityCache() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
    }
}

fun UiDevice.waitForFresh(selector: BySelector, timeoutMillis: Long, exists: Boolean = true): Boolean {
    val deadline = SystemClock.uptimeMillis() + timeoutMillis
    do {
        // Accessibility can retain a navigation item's pre-click selection after Compose renders.
        refreshAccessibilityCache()
        if (hasObject(selector) == exists) return true
        SystemClock.sleep(100)
    } while (SystemClock.uptimeMillis() < deadline)
    return false
}

fun MacrobenchmarkScope.prepareFixtures() = prepareFixtures(device)

fun prepareFixtures(device: UiDevice) {
    device.wakeUp()
    device.executeShellCommand("wm dismiss-keyguard")
    device.executeShellCommand("am start -W -S -n $TARGET_PACKAGE/$FIXTURE_ACTIVITY")
    if (!device.wait(Until.hasObject(By.text("BENCHMARK_READY")), 180_000)) {
        val failure = device.findObject(By.textStartsWith("BENCHMARK_FAILED"))?.text
        failJourney(device, "Synthetic fixture preparation failed: $failure")
    }
    device.pressHome()
}

fun MacrobenchmarkScope.openTab(tag: String) = openTab(device, tag)

fun openTab(device: UiDevice, tag: String) {
    val library = tag == "libraries" || tag.startsWith("library_")
    val destination = if (library) "libraries" else tag
    val navigation = when (destination) {
        "libraries" -> "Library|Libreria"
        "discovery" -> "Home"
        "search" -> "Search|Cerca"
        "releases" -> "Releases|Uscite"
        "more" -> "More|Altro"
        else -> error("Unknown benchmark destination: $destination")
    }
    clickFresh(device, By.text(java.util.regex.Pattern.compile(navigation)))
    if (library) {
        val label = when (tag) {
            "library_anime" -> "Anime"
            "library_manga" -> "Manga"
            else -> if (device.hasObject(By.text("All"))) "All" else "Tutti"
        }
        clickFresh(device, By.text(label))
    }
    awaitTabContent(device, destination)
}

fun clickFresh(device: UiDevice, selector: BySelector) {
    val deadline = SystemClock.uptimeMillis() + 10_000
    do {
        refreshAccessibilityCache()
        try {
            val bounds = device.findObject(selector)?.visibleBounds
            if (bounds != null && !bounds.isEmpty && device.click(bounds.centerX(), bounds.centerY())) return
        } catch (_: StaleObjectException) {
            // Rotation or a Compose update can replace the node between lookup and reading its bounds.
        }
        SystemClock.sleep(100)
    } while (SystemClock.uptimeMillis() < deadline)
    failJourney(device, "Could not click current navigation bounds: $selector")
}

fun awaitTabContent(device: UiDevice, tag: String) {
    if (!device.waitForFresh(By.res("content_$tag").hasDescendant(By.scrollable(true)), 15_000)) {
        failJourney(device, "Scrollable content did not load for: $tag")
    }
    // AnimatedContent briefly retains both screens; accessibility idle alone does not await Compose.
    for (previous in listOf("discovery", "libraries") - tag) {
        if (!device.waitForFresh(By.res("content_$previous"), 10_000, exists = false)) {
            failJourney(device, "Previous screen remained visible: $previous")
        }
    }
    if (tag == "libraries" &&
        !device.waitForFresh(
            By.res("content_$tag").hasDescendant(By.textStartsWith("Benchmark")),
            15_000,
        )
    ) {
        failJourney(device, "Library fixtures did not appear for: $tag")
    }
    device.waitForIdle()
}

fun MacrobenchmarkScope.scrollContent() {
    repeat(3) {
        device.waitForIdle()
        // Compose can replace an accessibility node after any state or image update.
        // Resolve fresh bounds for each gesture, without retaining a UiObject2 across frames.
        var bounds: android.graphics.Rect? = null
        for (attempt in 0..2) {
            try {
                bounds = device.wait(Until.findObject(By.scrollable(true)), 10_000)?.visibleBounds
                if (bounds != null) break
            } catch (e: StaleObjectException) {
                if (attempt == 2) throw e
            }
        }
        val area = checkNotNull(bounds) { "No scrollable content in benchmark journey" }
        check(
            device.swipe(
                area.centerX(),
                area.top + area.height() * 4 / 5,
                area.centerX(),
                area.top + area.height() / 5,
                20,
            ),
        )
        device.waitForIdle()
    }
}

fun MacrobenchmarkScope.openReader() {
    device.executeShellCommand("am start -W -n $TARGET_PACKAGE/$FIXTURE_ACTIVITY --ez reader true")
    check(device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "reader_pager")), 15_000)) {
        "The local manga reader did not open"
    }
    device.waitForIdle()
}

fun MacrobenchmarkScope.librarySearch() {
    val search = device.wait(Until.findObject(By.desc("Search")), 10_000)
        ?: device.findObject(By.desc("Cerca")) ?: error("Library search button missing")
    search.click()
    val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)
        ?: error("Search field missing")
    field.text = "Benchmark 002"
    device.waitForIdle()
}

fun MacrobenchmarkScope.coreJourneys() {
    startMainActivity()
    scrollContent()
    openTab("library_anime")
    scrollContent()
    librarySearch()
    device.pressBack()
    device.pressBack()
    openTab("library_manga")
    scrollContent()
    openReader()
}

@SuppressLint("RestrictedApi")
fun failJourney(device: UiDevice, message: String): Nothing {
    // Preserve the actual screen when a journey fails, including R8-only failures.
    runCatching {
        refreshAccessibilityCache()
        val name = "journey-failure-${System.currentTimeMillis()}"
        Outputs.writeFile("$name.png") { check(device.takeScreenshot(it)) }
        Outputs.writeFile("$name.xml") { device.dumpWindowHierarchy(it) }
    }
    error(message)
}

package tachiyomi.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AppInteractionBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    // Warm-start preparation can launch the app before setupBlock's iteration zero.
    @Before fun prepare() = prepareFixtures(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()))

    @Test fun homeScroll() = measure("discovery")

    @Test fun animeLibraryScroll() = measure("library_anime")

    @Test fun mangaLibraryScroll() = measure("library_manga")

    @Test fun unifiedLibraryScroll() = measure("libraries")

    private fun measure(tab: String) = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 5,
        setupBlock = {
            startMainActivity()
            openTab(tab)
        },
    ) { scrollContent() }

    @Test fun searchLargeAnimeLibrary() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 5,
        setupBlock = {
            startMainActivity()
            openTab("library_anime")
        },
    ) { librarySearch() }

    @Test fun openLocalMangaReader() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 5,
        setupBlock = {
            pressHome()
        },
    ) { openReader() }
}

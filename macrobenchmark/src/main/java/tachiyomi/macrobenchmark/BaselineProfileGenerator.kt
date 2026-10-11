package tachiyomi.macrobenchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Before fun prepare() = prepareFixtures(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()))

    @Test fun generate() = rule.collect(
        packageName = TARGET_PACKAGE,
        profileBlock = {
            coreJourneys()
        },
    )
}

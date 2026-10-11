plugins {
    id("mihon.library")
    kotlin("android")
}

android {
    namespace = "nyanime.privacy.display"
    defaultConfig {
        testInstrumentationRunner = "nyanime.privacy.display.PrivacyHardwareProbe"
    }
}

dependencies {
    testImplementation(libs.bundles.test)
}

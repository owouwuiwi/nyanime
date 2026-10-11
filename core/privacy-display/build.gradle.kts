plugins {
    id("mihon.library")
    kotlin("android")
}

extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
    namespace = "nyanime.privacy.display"
    defaultConfig {
        testInstrumentationRunner = "nyanime.privacy.display.PrivacyHardwareProbe"
    }
}

dependencies {
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.launcher)
}

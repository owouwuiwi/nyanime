plugins {
    id("mihon.library")
    kotlin("android")
    kotlin("plugin.serialization")
}

android {
    namespace = "nyanime.telegram"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "nyanime.telegram.TelegramNativeProbe"
    }
    sourceSets["main"].jniLibs.srcDir(rootProject.file(".local/telegram-native/jniLibs"))
}

dependencies {
    implementation(libs.telegram.phone)
    implementation(libs.telegram.sync.crypto)
    implementation(platform(kotlinx.coroutines.bom))
    implementation(kotlinx.bundles.coroutines)
    implementation(kotlinx.bundles.serialization)
    testImplementation(libs.bundles.test)
    testImplementation(kotlinx.coroutines.test)
}

plugins {
    id("mihon.library")
    kotlin("android")
    kotlin("plugin.serialization")
}

android {
    namespace = "nyanime.rooms.voice"
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
}

dependencies {
    implementation("io.github.webrtc-sdk:android:144.7559.15")
    implementation(platform(kotlinx.coroutines.bom))
    implementation(kotlinx.bundles.coroutines)
    implementation(kotlinx.bundles.serialization)
    testImplementation(libs.bundles.test)
}

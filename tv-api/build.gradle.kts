plugins {
    id("mihon.library")
    kotlin("android")
    kotlin("plugin.serialization")
}

android {
    namespace = "nyanime.tv.api"
    defaultConfig { consumerProguardFile("consumer-proguard.pro") }
}

dependencies {
    api(kotlinx.serialization.json)
    testImplementation(libs.bundles.test)
}

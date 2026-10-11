plugins {
    id("mihon.library")
    kotlin("android")
    kotlin("plugin.serialization")
}

android {
    namespace = "nyanime.news.api"
    defaultConfig { consumerProguardFile("consumer-proguard.pro") }
}

dependencies {
    api(kotlinx.serialization.json)
    testImplementation(libs.bundles.test)
}

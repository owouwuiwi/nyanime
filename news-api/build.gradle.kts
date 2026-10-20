plugins {
    id("mihon.library")
    kotlin("android")
    kotlin("plugin.serialization")
}

extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
    namespace = "nyanime.news.api"
    defaultConfig { consumerProguardFile("consumer-proguard.pro") }
}

dependencies {
    api(kotlinx.serialization.json)
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.launcher)
}

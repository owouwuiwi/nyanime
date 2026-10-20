plugins {
    id("mihon.library")
    kotlin("android")
    kotlin("plugin.serialization")
}

extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
    namespace = "tachiyomi.core.metadata"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    implementation(projects.sourceApi)

    implementation(kotlinx.bundles.serialization)
}

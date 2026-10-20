plugins {
    id("mihon.library")
    kotlin("android")
}
extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
    namespace = "nyanime.addon.api"
    defaultConfig { consumerProguardFile("consumer-proguard.pro") }
}

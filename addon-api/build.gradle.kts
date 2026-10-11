plugins {
    id("mihon.library")
    kotlin("android")
}
android {
    namespace = "nyanime.addon.api"
    defaultConfig { consumerProguardFile("consumer-proguard.pro") }
}

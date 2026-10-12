plugins {
    id("mihon.library")
    kotlin("android")
    kotlin("plugin.serialization")
}

android {
    namespace = "nyanime.search"
}

dependencies {
    implementation("com.darkrockstudios:symspellkt:3.4.0")
    implementation(platform(kotlinx.coroutines.bom))
    implementation(kotlinx.bundles.coroutines)
    implementation(kotlinx.bundles.serialization)
    testImplementation(libs.bundles.test)
    testImplementation(kotlinx.coroutines.test)
}

plugins {
    id("mihon.library")
    kotlin("android")
    kotlin("plugin.serialization")
}
extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
    namespace = "it.lorelore.nyanime.legado"
}
dependencies {
    implementation(projects.core.sourceStudio)
    implementation(platform(kotlinx.coroutines.bom))
    implementation(kotlinx.bundles.coroutines)
    api(kotlinx.bundles.serialization)
    implementation(libs.jsoup)
    implementation("com.google.re2j:re2j:1.7")
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(kotlinx.coroutines.test)
}

plugins {
    id("mihon.library")
    kotlin("android")
    kotlin("plugin.serialization")
}

tasks.withType<Test>().configureEach {
    providers.gradleProperty("studioFixture").orNull?.let { systemProperty("studio.fixture", it) }
}

extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
    namespace = "it.lorelore.nyanime.studio"
    defaultConfig.testInstrumentationRunner = "it.lorelore.nyanime.studio.document.DocumentWorkerProbe"
}

dependencies {
    implementation(projects.sourceApi)
    implementation(platform(kotlinx.coroutines.bom))
    implementation(kotlinx.bundles.coroutines)
    api(kotlinx.bundles.serialization)
    implementation(libs.jsoup)
    implementation(libs.okhttp.core)
    implementation(libs.bundles.js.engine)
    implementation("com.google.re2j:re2j:1.7")
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(kotlinx.coroutines.test)
}

plugins {
    id("mihon.library")
    kotlin("android")
}

extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
    namespace = "it.lorelore.nyanime.syncplay"
}

dependencies {
    api(platform(kotlinx.coroutines.bom))
    api(kotlinx.coroutines.core)
    implementation(kotlinx.serialization.json)
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    providers.gradleProperty("syncplayFixturePort").orNull?.let { systemProperty("syncplay.fixture.port", it) }
}

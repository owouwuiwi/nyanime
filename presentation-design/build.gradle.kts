plugins {
    id("mihon.library")
    id("mihon.library.compose")
    kotlin("android")
}

extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
    namespace = "it.lorelore.nyanime.presentation.design"
}

kotlin.compilerOptions.freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")

dependencies {
    api(compose.foundation)
    api(compose.material3.core)
    api(compose.haze)
    api(compose.backdrop)

    implementation(androidx.corektx)
    implementation(libs.material)

    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.launcher)
}

import java.util.Properties

plugins {
    id("mihon.benchmark")
}

// The runner is self-instrumenting, so its instrumentation target is the runner itself.
val appIdentity = Properties().apply { rootProject.file("app-identity.properties").inputStream().use(::load) }
val productionIdentity = providers.gradleProperty("productionIdentity").orElse("true").get().toBooleanStrict()
val targetAppId = if (productionIdentity) {
    appIdentity.getProperty("applicationId")
} else {
    appIdentity.getProperty("legacyApplicationId").removeSuffix(".debug")
}

extensions.configure<com.android.build.api.dsl.TestExtension>("android") {
    namespace = "tachiyomi.macrobenchmark"
    buildFeatures.buildConfig = true

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "TARGET_APP_ID", "\"$targetAppId.benchmark\"")
    }

    buildTypes {
        // This benchmark buildType is used for benchmarking, and should function like your
        // release build (for example, with minification on). It's signed with a debug key
        // for easy local/CI testing.
        create("benchmark") {
            isDebuggable = true
            signingConfig = getByName("debug").signingConfig
            matchingFallbacks.add("release")
        }
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(projects.core.smartSearch)
    implementation(androidx.test.ext)
    implementation(androidx.test.espresso.core)
    implementation(androidx.test.uiautomator)
    implementation(androidx.benchmark.macro)
}

androidComponents {
    beforeVariants(selector().all()) {
        it.enable = it.buildType == "benchmark"
    }
}

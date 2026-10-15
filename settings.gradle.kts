pluginManagement {
    // Kotlin 2.4 needs R8 9.1.29 or newer; AGP 8.9 bundles an older compiler.
    // https://developer.android.com/build/kotlin-support
    buildscript {
        repositories {
            mavenCentral()
            maven(url = "https://storage.googleapis.com/r8-releases/raw") {
                content { includeModule("com.android.tools", "r8") }
            }
        }
        dependencies {
            classpath("com.android.tools:r8:9.1.29")
        }
    }
    resolutionStrategy {
        eachPlugin {
            val regex = "com.android.(library|application)".toRegex()
            if (regex matches requested.id.id) {
                useModule("com.android.tools.build:gradle:${requested.version}")
            }
        }
    }
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven(url = "https://www.jitpack.io")
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("kotlinx") {
            from(files("gradle/kotlinx.versions.toml"))
        }
        create("androidx") {
            from(files("gradle/androidx.versions.toml"))
        }
        create("compose") {
            from(files("gradle/compose.versions.toml"))
        }
        create("aniyomilibs") {
            from(files("gradle/aniyomi.versions.toml"))
        }
    }
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Mirror only the existing immutable artifact; upstream version upgrades remain unrestricted.
        maven(url = uri("vendor/maven")) {
            content {
                includeVersion("com.github.arkon.FlexibleAdapter", "flexible-adapter", "c8013533")
                includeVersion("it.owouwuiwi.native", "nyanime-ffmpeg", "1.0.0")
                includeVersion("it.owouwuiwi.native", "nyanime-mpv", "1.0.0")
            }
        }
        mavenCentral()
        google()
        maven(url = "https://www.jitpack.io")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "Aniyomi"
include(":app")
include(":core-metadata")
include(":core:archive")
include(":core:common")
include(":core:panels")
include(":core:privacy-display")
include(":core:room-voice")
include(":core:smart-search")
include(":core:telegram")
include(":data")
include(":domain")
include(":i18n")
include(":i18n-aniyomi")
include(":macrobenchmark")
include(":presentation-core")
include(":presentation-widget")
include(":source-api")
include(":news-api")
include(":tv-api")
include(":addon-api")
include(":source-local")

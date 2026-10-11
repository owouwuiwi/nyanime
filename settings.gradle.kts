pluginManagement {
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
                includeVersion("org.rustls", "rustls-platform-verifier", "0.2.0")
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
include(":core:attention")
include(":core:common")
include(":core:panels")
include(":core:reader-quality")
include(":core:novel-reader")
include(":core:legado")
include(":core:privacy-display")
include(":core:room-voice")
include(":core:smart-search")
include(":core:source-studio")
include(":core:source-exchange")
include(":core:syncplay")
include(":core:telegram")
include(":data")
include(":domain")
include(":i18n")
include(":i18n-aniyomi")
include(":macrobenchmark")
include(":presentation-core")
include(":presentation-player")
include(":presentation-widget")
include(":source-api")
include(":news-api")
include(":tv-api")
include(":addon-api")
include(":source-local")

include(":feature:secret-game")

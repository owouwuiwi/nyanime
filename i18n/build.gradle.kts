import mihon.buildlogic.generatedBuildDir
import mihon.buildlogic.tasks.EnglishFallbackResourcesTask
import mihon.buildlogic.tasks.getLocalesConfigTask
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    id("mihon.library")
    kotlin("multiplatform")
    alias(libs.plugins.moko)
}

kotlin {
    androidTarget()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                api(libs.moko.core)
            }
        }
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

val generatedAndroidResourceDir = generatedBuildDir.resolve("android/res")

val englishFallback = tasks.register<EnglishFallbackResourcesTask>("prepareEnglishFallback") {
    inputDirectory.set(layout.projectDirectory.dir("src/commonMain/moko-resources"))
    outputDirectory.set(layout.buildDirectory.dir("generated/englishFallback"))
    baseFolder.set("base")
}

extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
    namespace = "tachiyomi.i18n"

    sourceSets {
        getByName("main").res.srcDirs(
            "src/commonMain/resources",
            generatedAndroidResourceDir,
        )
    }

    lint {
        disable.addAll(listOf("MissingTranslation", "ExtraTranslation"))
    }
}

multiplatformResources {
    resourcesSourceSets.getByName("commonMain").setSrcDirs(
        listOf(englishFallback.flatMap { it.outputDirectory }),
    )
    resourcesPackage.set("tachiyomi.i18n")
}

tasks {
    matching { it.name.startsWith("generateMR") }.configureEach { dependsOn(englishFallback) }
    val localesConfigTask = project.getLocalesConfigTask(generatedAndroidResourceDir)
    preBuild {
        dependsOn(localesConfigTask)
    }
}

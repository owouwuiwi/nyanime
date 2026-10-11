plugins {
    id("mihon.library")
    id("mihon.library.compose")
    kotlin("android")
}

// Original upstream snapshots are provenance inputs, not formatter-owned code.
configure<com.diffplug.gradle.spotless.SpotlessExtension> {
    kotlin {
        target("src/**/*.kt", "build.gradle.kts")
        targetExclude("**/build/**/*.kt", "third-party/**")
    }
}

abstract class GeneratePlayerPresentationNotices : DefaultTask() {
    @get:InputFile
    abstract val originalLicense: RegularFileProperty

    @get:InputFile
    abstract val thirdPartyLicense: RegularFileProperty

    @get:InputFile
    abstract val notice: RegularFileProperty

    @get:InputFile
    abstract val flowLicense: RegularFileProperty

    @get:InputFile
    abstract val flowNotice: RegularFileProperty

    @get:OutputDirectory
    abstract val assets: DirectoryProperty

    @TaskAction
    fun generate() {
        val folder = assets.get().dir("licenses").asFile.apply { mkdirs() }
        listOf(
            "PlayerPresentation-LICENSE.txt" to originalLicense,
            "PlayerPresentation-Apache-2.0.txt" to thirdPartyLicense,
            "PlayerPresentation-NOTICE.txt" to notice,
            "FlowPlayerStyle-LICENSE.txt" to flowLicense,
            "FlowPlayerStyle-NOTICE.txt" to flowNotice,
        ).forEach { (name, source) ->
            val target = folder.resolve(name)
            val bytes = source.get().asFile.readBytes()
            if (!target.exists() || !target.readBytes().contentEquals(bytes)) target.writeBytes(bytes)
        }
    }
}

val playerNotices = tasks.register<GeneratePlayerPresentationNotices>("generatePlayerPresentationNotices") {
    originalLicense.set(layout.projectDirectory.file("LICENSE"))
    thirdPartyLicense.set(layout.projectDirectory.file("third-party/Apache-2.0.txt"))
    notice.set(layout.projectDirectory.file("NOTICE"))
    flowLicense.set(layout.projectDirectory.file("third-party/flow-ui/LICENSE"))
    flowNotice.set(layout.projectDirectory.file("third-party/flow-ui/NOTICE"))
    assets.set(layout.buildDirectory.dir("generated/player-notices"))
}

extensions.getByType<com.android.build.api.variant.LibraryAndroidComponentsExtension>().onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(playerNotices) { it.assets }
}

extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
    namespace = "it.lorelore.nyanime.presentation.player"
    defaultConfig {
        testInstrumentationRunner = "it.lorelore.nyanime.presentation.player.PlayerPresentationProbe"
    }
}

kotlin.compilerOptions.freeCompilerArgs.addAll(
    "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
    "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
    "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
)

dependencies {
    api(compose.foundation)
    api(compose.material3.core)
    implementation(compose.animation)
    implementation(compose.material.icons)
    implementation(compose.activity)
    implementation(kotlinx.coroutines.android)
    implementation(platform(libs.coil.bom))
    implementation(libs.coil.compose)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.launcher)
    androidTestImplementation(compose.activity)
}

val verifyPlayerPresentationBoundary = tasks.register("verifyPlayerPresentationBoundary") {
    group = "verification"
    description = "Keep the host renderer independent of optional feature runtimes."
    notCompatibleWithConfigurationCache("Inspects the renderer dependency graph")
    inputs.dir("src/main")
    doLast {
        val forbiddenImports =
            Regex(
                "(?m)^import\\s+(io\\.github\\.aedev\\.flow|nyanime\\.video|org\\.schabi\\.newpipe|com\\.metrolist)(\\.|$)",
            )
        fileTree("src/main").matching { include("**/*.kt") }.forEach { source ->
            check(!forbiddenImports.containsMatchIn(source.readText())) {
                "Player presentation imports a feature runtime: ${source.relativeTo(projectDir)}"
            }
        }
        configurations.filter {
            it.isCanBeResolved &&
                it.name in setOf(
                    "debugCompileClasspath",
                    "debugRuntimeClasspath",
                    "releaseCompileClasspath",
                    "releaseRuntimeClasspath",
                )
        }.forEach { configuration ->
            configuration.incoming.resolutionResult.allComponents.forEach { component ->
                val projectId = component.id as? org.gradle.api.artifacts.component.ProjectComponentIdentifier
                check(projectId == null || projectId.projectPath == project.path) {
                    "Player presentation depends on another host/feature project: ${component.id}"
                }
                val module = component.moduleVersion
                check(module == null || !module.group.contains("aedev", ignoreCase = true)) {
                    "Player presentation depends on an optional video runtime: $module"
                }
            }
        }
        logger.lifecycle("PLAYER_PRESENTATION_BOUNDARY_OK")
    }
}

tasks.named("preBuild") { dependsOn(verifyPlayerPresentationBoundary) }

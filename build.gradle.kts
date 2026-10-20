plugins {
    alias(kotlinx.plugins.serialization) apply false
    alias(libs.plugins.aboutLibraries) apply false
    alias(libs.plugins.moko) apply false
    alias(libs.plugins.sqldelight) apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

val verifyComposeAlignment = tasks.register("verifyComposeAlignment") {
    group = "verification"
    description = "Reject mismatched Material3 compile/runtime versions across the host and integrated UI modules."
    doLast {
        val selected = inputs.files.files.flatMap { it.readLines() }
        check(selected.isNotEmpty() && selected.map { it.substringAfter("=") }.toSet().size == 1) {
            "Material3 must match in every UI compile/runtime classpath:\n" +
                selected.joinToString("\n")
        }
        logger.lifecycle("COMPOSE_ALIGNMENT_OK: Material3 ${selected.first().substringAfter("=")} across ${selected.size} classpaths")
    }
}

subprojects {
    if (path in setOf(":app", ":presentation-player")) {
        // Resolve each graph in its owning project, under Gradle's project lock.
        val report = layout.buildDirectory.file("reports/compose-alignment.txt")
        val writeComposeVersions = tasks.register("writeComposeVersions") {
            notCompatibleWithConfigurationCache("Inspects the project's dependency graphs")
            outputs.file(report)
            outputs.upToDateWhen { false }
            doLast {
                val selected = configurations.filter {
                    it.isCanBeResolved && it.name in setOf(
                        "debugCompileClasspath", "debugRuntimeClasspath",
                        "releaseCompileClasspath", "releaseRuntimeClasspath",
                        "previewCompileClasspath", "previewRuntimeClasspath",
                    )
                }.map { configuration ->
                    val versions = configuration.incoming.resolutionResult.allComponents.mapNotNull {
                        it.moduleVersion?.takeIf { module ->
                            module.group == "androidx.compose.material3" &&
                                module.name in setOf("material3", "material3-android")
                        }?.version
                    }.toSet()
                    check(versions.size == 1) { "Missing or conflicting Material3 in $path:${configuration.name}: $versions" }
                    "$path:${configuration.name}=${versions.single()}"
                }
                check(selected.isNotEmpty()) { "No UI classpaths checked in $path" }
                report.get().asFile.apply { parentFile.mkdirs() }.writeText(selected.joinToString("\n"))
            }
        }
        verifyComposeAlignment.configure {
            dependsOn(writeComposeVersions)
            inputs.files(writeComposeVersions)
        }
    }
    plugins.withId("com.android.application") {
        tasks.named("preBuild") { dependsOn(verifyComposeAlignment) }
    }
}

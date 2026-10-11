import groovy.json.JsonSlurper
import java.security.MessageDigest

plugins {
    id("mihon.library")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
    namespace = "nyanime.secretgame"
    defaultConfig.testInstrumentationRunner = "nyanime.secretgame.SecretGameDeviceProbe"
    buildFeatures.compose = true
    androidResources { noCompress += "wasm" }
}

dependencies {
    implementation(platform(compose.bom))
    implementation(compose.foundation)
    implementation(compose.material3.core)
    implementation(compose.material.icons)
    implementation(compose.activity)
    implementation(libs.androidx.webkit)
    implementation(androidx.lifecycle.runtime.compose)
    implementation(platform(kotlinx.coroutines.bom))
    implementation(kotlinx.coroutines.android)
    implementation(kotlinx.serialization.json)
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.launcher)
}

val verifySecretGameAssets by tasks.registering {
    val game = file("src/main/assets/secret-game/sandspiel")
    val source = rootProject.file("vendor/sandspiel")
    inputs.dir(game)
    inputs.files(rootProject.fileTree("tools/sandspiel") { exclude("node_modules/**", ".work/**") })
    inputs.dir(source.resolve("crate/src"))
    inputs.dir(source.resolve("js/glsl"))
    inputs.files(
        source.resolve("js/fluid.js"),
        source.resolve("js/render.js"),
        source.resolve("js/fluidShaders.js"),
        source.resolve("LICENSE"),
    )
    doLast {
        @Suppress("UNCHECKED_CAST")
        val provenance = JsonSlurper().parse(game.resolve("provenance.json")) as Map<String, Any>
        check(provenance["revision"] == "9936e06b07a583aedfba9589c0cead2917f405fb") {
            "Review the pinned game revision before rebuilding."
        }
        if (source.resolve(".git").exists()) {
            val revision = providers.exec {
                commandLine("git", "-C", source.absolutePath, "rev-parse", "HEAD")
            }.standardOutput.asText.get().trim()
            check(revision == provenance["revision"]) { "Initialize the reviewed vendor/sandspiel submodule revision." }
        }
        fun digest(
            bytes: ByteArray,
        ) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        @Suppress("UNCHECKED_CAST")
        val assets = provenance["assets"] as Map<String, String>
        assets.forEach { (name, expected) ->
            check(digest(game.resolve(name).readBytes()) == expected) {
                "Unverified game asset $name; rebuild with tools/sandspiel."
            }
        }
        @Suppress("UNCHECKED_CAST")
        val sources = provenance["sources"] as Map<String, String>
        sources.forEach { (name, expected) ->
            check(source.resolve(name).isFile) { "Initialize the pinned vendor/sandspiel Git submodule." }
            val canonical = source.resolve(name).readText().replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
            check(digest(canonical) == expected) {
                "Sandspiel source $name changed; review and rebuild the isolated adapter."
            }
        }
        @Suppress("UNCHECKED_CAST")
        val adapter = provenance["adapter"] as Map<String, String>
        adapter.forEach { (name, expected) ->
            val canonical = rootProject.file(
                "tools/sandspiel/$name",
            ).readText().replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
            check(digest(canonical) == expected) {
                "Game adapter $name changed; rebuild the bundled assets with tools/sandspiel."
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(verifySecretGameAssets) }

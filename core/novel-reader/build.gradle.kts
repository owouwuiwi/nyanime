import java.security.MessageDigest

plugins {
    id("mihon.library")
    kotlin("android")
    kotlin("plugin.serialization")
}

val readiumProvenance = configurations.create("readiumProvenance") {
    isCanBeConsumed = false
    isTransitive = false
}

extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
    namespace = "it.lorelore.nyanime.novel"
    defaultConfig {
        testInstrumentationRunner = "it.lorelore.nyanime.novel.NovelRuntimeProbe"
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    listOf("shared", "streamer", "navigator").forEach {
        add(readiumProvenance.name, "org.readium.kotlin-toolkit:readium-$it:3.4.0@aar")
    }
    implementation(platform(kotlinx.coroutines.bom))
    implementation(kotlinx.bundles.coroutines)
    implementation(kotlinx.serialization.json)
    implementation(libs.jsoup)
    api("org.readium.kotlin-toolkit:readium-shared:3.4.0")
    api("org.readium.kotlin-toolkit:readium-streamer:3.4.0")
    // This AAR embeds PhotoView2.3.0; remove the duplicate direct app dependency
    // only after verifying the existing22class hashes against these pinned bytes.
    api("org.readium.kotlin-toolkit:readium-navigator:3.4.0") {
        // Only the EPUB navigator is used. Its optional audiobook classes must not
        // upgrade the application's independently tested video playback stack.
        exclude(group = "androidx.media3")
    }
    androidTestImplementation(androidx.appcompat)
    // The real host already uses this alias. A probe must not silently test an older WebKit.
    androidTestImplementation(libs.androidx.webkit)
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(kotlinx.coroutines.test)
}

val verifyReadium = tasks.register("verifyReadium") {
    inputs.files(readiumProvenance)
    doLast {
        val expected = mapOf(
            "readium-shared-3.4.0.aar" to "cdace078c2310699486f53fe95bc1b7d9da0f377228fc8d3e432599be8a4d1ea",
            "readium-streamer-3.4.0.aar" to "dbf4e417495105abe6b7558d17577c826799fb0eba2bd9d0c4ae85bcfc98059d",
            "readium-navigator-3.4.0.aar" to "74b25a7cfe7c456272d5dfce2d56ba1ccea0fe8ee9115afe2c9241ff2232b9b1",
        )
        val actual = readiumProvenance.files.associate { file ->
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            file.name to digest.digest().joinToString("") { "%02x".format(it) }
        }
        check(actual == expected) { "Readium artifacts differ from the reviewed 3.4.0 bytes and notices" }
    }
}

tasks.named("preBuild") { dependsOn(verifyReadium) }

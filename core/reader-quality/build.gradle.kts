import java.io.File
import java.security.MessageDigest

plugins {
    id("mihon.library")
    kotlin("android")
}

extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
    namespace = "it.lorelore.nyanime.reader.quality"
    sourceSets["main"].jniLibs.srcDir(rootProject.file(".local/reader-quality-native/jniLibs"))
}

dependencies {
    implementation(androidx.annotation)
    implementation(platform(kotlinx.coroutines.bom))
    implementation(kotlinx.bundles.coroutines)
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(kotlinx.coroutines.test)
}

val verifyReaderQualityNative = tasks.register("verifyReaderQualityNative") {
    val directory = rootProject.file(".local/reader-quality-native/jniLibs")
    val source = project.file("src/main/cpp")
    inputs.dir(directory)
    inputs.dir(source)
    doLast {
        fun sha(file: File): String = file.inputStream().use { stream ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64").forEach { abi ->
            val library = directory.resolve("$abi/libnyanime_reader_quality.so")
            val proof = directory.resolve("$abi/provenance.json")
            check(library.isFile && proof.isFile) {
                "Missing reader image engine for $abi; run python3 -m scripts.restore_reader_quality_native"
            }
            val metadata = proof.readText()
            check(
                metadata.contains("56775de50990ab7f16627efdcf5529b49541206f") &&
                    metadata.contains("28.2.13676358") &&
                    metadata.contains(abi) &&
                    metadata.contains(sha(library)) &&
                    metadata.contains(sha(source.resolve("CMakeLists.txt"))) &&
                    metadata.contains(sha(source.resolve("reader_quality.cpp"))),
            ) { "Reader native engine differs from its source or provenance: $abi" }
        }
    }
}
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("NativeLibs")) dependsOn(verifyReaderQualityNative)
}

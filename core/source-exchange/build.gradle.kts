import java.security.MessageDigest

plugins {
    id("mihon.library")
    kotlin("android")
}

extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
    namespace = "it.lorelore.nyanime.exchange.engine"
    defaultConfig { consumerProguardFiles("consumer-proguard.pro") }
}

dependencies { implementation("org.rustls:rustls-platform-verifier:0.2.0") }

val verifySourceExchangeNative = tasks.register("verifySourceExchangeNative") {
    val manifest = layout.projectDirectory.file("native/android/provenance.json")
    inputs.file(manifest)
    inputs.dir(layout.projectDirectory.dir("src/main/jniLibs"))
    inputs.files(fileTree("native/src") { include("*.rs") }, file("native/Cargo.toml"), file("native/Cargo.lock"))
    val tlsProvenance = rootProject.file("tools/source-exchange/rustls-android.json")
    inputs.file(tlsProvenance)
    inputs.dir(rootProject.file("vendor/maven/org/rustls/rustls-platform-verifier/0.2.0"))
    doLast {
        val data = groovy.json.JsonSlurper().parse(manifest.asFile) as Map<*, *>
        fun digest(file: java.io.File) = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes()).joinToString("") { byte -> "%02x".format(byte) }
        val tls = groovy.json.JsonSlurper().parse(tlsProvenance) as Map<*, *>
        check(tls["version"] == "0.2.0") { "Review the Rustls Android support version before changing it." }
        val lock = file("native/Cargo.lock").readText()
        check(lock.contains("name = \"rustls-platform-verifier-android\"\nversion = \"0.2.0\""))
        for ((suffix, hashKey) in listOf("aar" to "aarSha256", "pom" to "pomSha256")) {
            val artifact = rootProject.file("vendor/maven/${tls["path"]}.$suffix")
            check(artifact.isFile && digest(artifact) == tls[hashKey]) {
                "Unverified Rustls Android support artifact: $suffix"
            }
        }
        val sourceArchive = rootProject.file(tls["sourceArchive"] as String)
        check(sourceArchive.isFile && digest(sourceArchive) == tls["sourceArchiveSha256"]) {
            "Original Android TLS verifier source archive is missing or changed."
        }
        val hashes = data["inputs"] as Map<*, *>
        hashes.forEach { (name, expected) ->
            val input = file("native/$name")
            check(input.isFile && digest(input) == expected) {
                "Source exchange native input changed: $name. Rebuild the native engine."
            }
        }
        val binaries = data["binaries"] as Map<*, *>
        check(binaries.isNotEmpty()) { "Build source exchange native libraries first." }
        val packaged = fileTree("src/main/jniLibs") {
            include("*/libnyanime_source_exchange.so")
        }.files.map { it.parentFile.name }.toSet()
        check(packaged == binaries.keys) { "Native ABI inputs belong to different source generations." }
        binaries.forEach { (abi, expected) ->
            val binary = file("src/main/jniLibs/$abi/libnyanime_source_exchange.so")
            check(binary.isFile && digest(binary) == expected) { "Unverified source exchange native library: $abi" }
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(verifySourceExchangeNative) }

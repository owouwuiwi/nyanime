import java.security.MessageDigest

plugins {
    id("mihon.library")
    kotlin("android")
}

extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
    namespace = "it.lorelore.nyanime.attention.core"
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
    androidResources { noCompress += "task" }
}

dependencies {
    implementation("com.google.mediapipe:tasks-vision:0.10.35")
    implementation(androidx.camera.core)
    implementation(androidx.camera.camera2)
    implementation(androidx.camera.lifecycle)
    implementation(platform(kotlinx.coroutines.bom))
    implementation(kotlinx.bundles.coroutines)
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.launcher)
}

val verifyAttentionModel by tasks.registering {
    val model = file("src/main/assets/attention/face_landmarker.task")
    inputs.file(model)
    doLast {
        val expected = file("model.sha256").readText().trim()
        val actual = MessageDigest.getInstance("SHA-256").digest(model.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(actual == expected) { "Attention model provenance changed; review the model before building" }
    }
}
tasks.named("preBuild") { dependsOn(verifyAttentionModel) }

import java.security.MessageDigest

plugins {
    id("mihon.library")
    kotlin("android")
    kotlin("plugin.serialization")
}

android {
    namespace = "nyanime.panels"
    androidResources { noCompress += "tflite" }
}

dependencies {
    implementation("com.google.ai.edge.litert:litert:1.4.2")
    implementation(platform(kotlinx.coroutines.bom))
    implementation(kotlinx.bundles.coroutines)
    implementation(kotlinx.bundles.serialization)
    testImplementation(libs.bundles.test)
    testImplementation(kotlinx.coroutines.test)
}

val verifyPanelModel by tasks.registering {
    val model = file("src/main/assets/deeppanel/model.tflite")
    inputs.file(model)
    doLast {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(model.readBytes()).joinToString("") { "%02x".format(it) }
        check(
            model.length() == 12_532_336L &&
                digest == "93347aad3abd846f8f993955764fe3279ff6d347cec401e6c2393856f5a8562e",
        ) {
            "Unexpected DeepPanel model; review provenance before changing it"
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyPanelModel) }

import mihon.buildlogic.configureCompose

plugins {
    id("com.android.application")

    id("mihon.code.lint")
}

// Apply after the host migration properties are available; the schema probe has no project properties.
apply(plugin = "org.jetbrains.kotlin.android")

extensions.configure<com.android.build.api.dsl.ApplicationExtension>("android") {
    configureCompose(this)
}

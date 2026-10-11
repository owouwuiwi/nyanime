import mihon.buildlogic.configureCompose

plugins {
    id("com.android.library")

    id("mihon.code.lint")
}

extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
    configureCompose(this)
}

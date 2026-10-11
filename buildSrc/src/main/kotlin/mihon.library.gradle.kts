import mihon.buildlogic.configureAndroid
import mihon.buildlogic.configureTest

plugins {
    id("com.android.library")

    id("mihon.code.lint")
}

extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
    configureAndroid(this)
    configureTest()
}

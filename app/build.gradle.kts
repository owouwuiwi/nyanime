import mihon.buildlogic.Config
import mihon.buildlogic.getBuildTime
import mihon.buildlogic.getCommitCount
import mihon.buildlogic.getGitSha
import mihon.buildlogic.tasks.EnglishFallbackResourcesTask
import mihon.buildlogic.tasks.PrepareShortcutsTask
import java.util.Properties

plugins {
    id("mihon.android.application")
    id("mihon.android.application.compose")
    kotlin("plugin.serialization")
    alias(libs.plugins.aboutLibraries)
    id("com.android.compose.screenshot") version "0.0.1-alpha15"
}

androidComponents.onVariants { variant ->
    variant.instrumentation.transformClassesWith(
        it.lorelore.nyanime.buildlogic.LocalMediaPipeLogging::class.java,
        com.android.build.api.instrumentation.InstrumentationScope.ALL,
    ) {}
    val shortcuts = tasks.register<PrepareShortcutsTask>(
        "prepare${variant.name.replaceFirstChar(Char::uppercase)}Shortcuts",
    ) {
        template.set(layout.projectDirectory.file("shortcuts.xml"))
        targetPackage.set(variant.applicationId)
        outputDirectory.set(layout.buildDirectory.dir("generated/shortcuts/${variant.name}"))
    }
    variant.sources.res?.addGeneratedSourceDirectory(shortcuts) { it.outputDirectory }
}

// Exercise the same application variant and library graph that is signed for the device.
androidComponents.beforeVariants(androidComponents.selector().withBuildType("preview")) {
    it.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
}

aboutLibraries {
    configPath = file("config").absolutePath
}

// A restored branch can select the same artifacts with different declarations/notices.
tasks.matching { it.name.startsWith("collectDependencies") }.configureEach {
    inputs.files(rootProject.allprojects.map { it.buildFile })
        .withPropertyName("dependencyDeclarations")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(rootProject.fileTree("gradle") { include("*.versions.toml") })
        .withPropertyName("dependencyCatalogs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(layout.projectDirectory.dir("config"))
        .withPropertyName("attributionOverrides")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// Copy the canonical project texts so offline attribution cannot drift from GitHub.
abstract class GeneratedProjectAssets : Sync() {
    @get:OutputDirectory
    abstract val generatedDirectory: DirectoryProperty
}

val verifyOrdinaryRuntimeBoundary = tasks.register("verifyOrdinaryRuntimeBoundary") {
    group = "verification"
    description = "Keep the ordinary application free of the excluded video and music engines."
    notCompatibleWithConfigurationCache("Inspects the application's dependency graphs")
    val report = layout.buildDirectory.file("reports/ordinary-runtime-dependencies.json")
    outputs.file(report)
    outputs.upToDateWhen { false }
    doLast {
        val forbidden = Regex("(?i)(aedev|newpipe|metrolist|innertubex|tinypinyin)")
        val resolved = configurations.filter {
            it.isCanBeResolved && it.name in setOf("previewCompileClasspath", "previewRuntimeClasspath")
        }.associate { configuration ->
            configuration.name to configuration.incoming.resolutionResult.allComponents.map { component ->
                val projectId = component.id as? org.gradle.api.artifacts.component.ProjectComponentIdentifier
                check(
                    projectId?.projectPath !in
                        setOf(
                            ":feature:online-video",
                            ":feature:music",
                            ":feature:music:innertube",
                            ":feature:music:quickjs",
                        ),
                ) {
                    "Excluded feature in ordinary application: ${component.id}"
                }
                val module = component.moduleVersion?.toString() ?: component.id.displayName
                check(!forbidden.containsMatchIn(module)) { "Excluded runtime dependency: $module" }
                module
            }.sorted()
        }
        check(resolved.size == 2) { "The ordinary preview compile/runtime graphs were not checked" }
        report.get().asFile.apply { parentFile.mkdirs() }
            .writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(resolved)))
        logger.lifecycle("ORDINARY_RUNTIME_BOUNDARY_OK")
    }
}
tasks.named("preBuild") { dependsOn(verifyOrdinaryRuntimeBoundary) }

val generateProjectLicenses by tasks.registering(GeneratedProjectAssets::class) {
    generatedDirectory.set(layout.buildDirectory.dir("generated/projectLicenses"))
    from(rootProject.file("LICENSE")) { rename { "Nyanime-LICENSE.txt" } }
    from(rootProject.file("NOTICE")) { rename { "Nyanime-NOTICE.txt" } }
    into(generatedDirectory.dir("licenses"))
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(generateProjectLicenses) { it.generatedDirectory }
}
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("Assets")) {
        dependsOn(generateProjectLicenses)
    }
}

val englishFallback = tasks.register<EnglishFallbackResourcesTask>("prepareEnglishFallback") {
    inputDirectory.set(layout.projectDirectory.dir("src/main/res"))
    outputDirectory.set(layout.buildDirectory.dir("generated/englishFallback"))
    baseFolder.set("values")
}

val telegramProperties = Properties().apply {
    rootProject.file(".local/telegram.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
val telegramApiId = providers.environmentVariable("TELEGRAM_API_ID").orNull
    ?: telegramProperties.getProperty("api_id", "0")
val telegramApiHash = providers.environmentVariable("TELEGRAM_API_HASH").orNull
    ?: telegramProperties.getProperty("api_hash", "")
require(
    telegramApiId.toIntOrNull() != null &&
        (telegramApiHash.isEmpty() || telegramApiHash.matches(Regex("[a-fA-F0-9]{32}"))),
) {
    "Invalid Telegram application configuration"
}
require((telegramApiId.toInt() == 0) == telegramApiHash.isEmpty()) {
    "Telegram API ID and hash must be configured together"
}
if (providers.gradleProperty("requireTelegramConfiguration").orNull == "true") {
    require(telegramApiId.toInt() > 0 && telegramApiHash.isNotEmpty()) {
        "Configure the Telegram application credentials before publishing Cloud"
    }
}
val publicRelease = Properties().apply {
    rootProject.file("release.properties").inputStream().use(::load)
}
val publicVersion = publicRelease.getProperty("versionName")
require(publicVersion.matches(Regex("(0|[1-9]\\d*)(\\.(0|[1-9]\\d*)){3}"))) { "Invalid four-part public version" }
val publicVersionCode = publicRelease.getProperty("versionCode").toInt()
require(publicVersionCode in 134..2_100_000_000) { "Invalid Android versionCode" }
// Published builds use the production identity. The old identity is retained only
// for explicit local migration diagnostics, never for new releases.
val productionIdentity = providers.gradleProperty("productionIdentity").orElse("true").get().toBooleanStrict()
val appIdentity = Properties().apply { rootProject.file("app-identity.properties").inputStream().use(::load) }

extensions.configure<com.android.build.api.dsl.ApplicationExtension>("android") {
    experimentalProperties["android.experimental.enableScreenshotTest"] = true
    namespace = "eu.kanade.tachiyomi"

    defaultConfig {
        applicationId =
            if (productionIdentity) appIdentity.getProperty("applicationId") else "xyz.jmir.tachiyomi.mi.anime4k"

        versionCode = publicVersionCode
        versionName = publicVersion
        buildConfigField("String", "PRODUCTION_APPLICATION_ID", "\"${appIdentity.getProperty("applicationId")}\"")
        buildConfigField("String", "PRODUCTION_CERT_SHA256", "\"${appIdentity.getProperty("certificateSha256")}\"")
        buildConfigField("String", "LEGACY_APPLICATION_ID", "\"${appIdentity.getProperty("legacyApplicationId")}\"")
        buildConfigField("String", "LEGACY_CERT_SHA256", "\"${appIdentity.getProperty("legacyCertificateSha256")}\"")
        buildConfigField(
            "String",
            "RELEASE_ASSET_PREFIX",
            if (productionIdentity) "\"Nyanime-Production\"" else "\"Nyanime\"",
        )

        buildConfigField("String", "COMMIT_COUNT", "\"${getCommitCount()}\"")
        buildConfigField("String", "COMMIT_SHA", "\"${getGitSha()}\"")
        buildConfigField("String", "BUILD_TIME", "\"${getBuildTime(useLastCommitTime = false)}\"")
        buildConfigField("boolean", "UPDATER_ENABLED", "${Config.enableUpdater}")
        // Real-server probes use a separate temporary archive; never run the user's cloud jobs in that build.
        buildConfigField(
            "boolean",
            "ISOLATED_CLOUD_PROBE",
            "${providers.gradleProperty("cloudSyncProbe").orNull == "true"}",
        )
        buildConfigField("int", "TELEGRAM_API_ID", telegramApiId)
        buildConfigField("String", "TELEGRAM_API_HASH", "\"$telegramApiHash\"")

        // Put these fields in acra.properties
        // val acraProperties = Properties()
        // rootProject.file("acra.properties")
        //     .takeIf { it.exists() }
        //     ?.let { acraProperties.load(FileInputStream(it)) }
        // val acraUri = acraProperties.getProperty("ACRA_URI", "")
        // val acraLogin = acraProperties.getProperty("ACRA_LOGIN", "")
        // val acraPassword = acraProperties.getProperty("ACRA_PASSWORD", "")
        // buildConfigField("String", "ACRA_URI", "\"$acraUri\"")
        // buildConfigField("String", "ACRA_LOGIN", "\"$acraLogin\"")
        // buildConfigField("String", "ACRA_PASSWORD", "\"$acraPassword\"")

        testInstrumentationRunner = if (providers.gradleProperty("updateProbe").orNull == "true") {
            "eu.kanade.tachiyomi.updater.UpdatePageDeviceProbe"
        } else if (providers.gradleProperty("remoteBackupProbe").orNull == "true") {
            "it.lorelore.nyanime.backup.remote.RemoteBackupDeviceProbe"
        } else if (providers.gradleProperty("cloudSyncProbe").orNull == "true") {
            "eu.kanade.tachiyomi.cloud.CloudSyncDeviceProbe"
        } else if (providers.gradleProperty("telegramProbe").orNull == "true") {
            "eu.kanade.tachiyomi.telegram.TelegramIpcProbe"
        } else if (providers.gradleProperty("downloadProbe").orNull == "true") {
            "eu.kanade.tachiyomi.download.DownloadDeviceProbe"
        } else if (providers.gradleProperty("rinProbe").orNull == "true") {
            "eu.kanade.tachiyomi.rin.RinDeviceProbe"
        } else {
            "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    val productionSigning = if (productionIdentity) {
        signingConfigs.create("production") {
            // Debug/unit-test tasks need no production credentials. A publishable
            // variant fails signing validation if these protected inputs are absent.
            storeFile = providers.environmentVariable("NYANIME_SIGNING_STORE_FILE").orNull?.let(::file)
            storePassword = providers.environmentVariable("NYANIME_SIGNING_STORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("NYANIME_SIGNING_KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("NYANIME_SIGNING_KEY_PASSWORD").orNull
        }
    } else {
        null
    }
    buildTypes {
        val debug by getting {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-${getCommitCount()}"
            isPseudoLocalesEnabled = true
        }
        val release by getting {
            isMinifyEnabled = Config.enableCodeShrink
            isShrinkResources = Config.enableCodeShrink

            proguardFiles("proguard-android-optimize.txt", "proguard-rules.pro")

            buildConfigField("String", "BUILD_TIME", "\"${getBuildTime(useLastCommitTime = true)}\"")
        }

        val commonMatchingFallbacks = listOf(release.name)

        create("preview") {
            initWith(release)
            applicationIdSuffix = if (productionIdentity) "" else ".debug"

            versionNameSuffix = ""
            signingConfig = productionSigning ?: debug.signingConfig
            // This is the signed OTA variant; the publication channel is separate.
            buildConfigField("boolean", "UPDATER_ENABLED", "true")

            matchingFallbacks.addAll(commonMatchingFallbacks)

            if (providers.gradleProperty("rinProbe").orNull == "true") {
                proguardFiles("rin-probe-rules.pro")
            }
            if (providers.gradleProperty("migrationProbe").orNull == "true") {
                proguardFiles("migration-probe-rules.pro")
            }
            // Device probes call these entry points from a separate test APK.
            if (providers.gradleProperty("remoteBackupProbe").orNull == "true") {
                proguardFiles("remote-backup-probe-rules.pro")
            }
            if (providers.gradleProperty("downloadProbe").orNull == "true") {
                proguardFiles("download-probe-rules.pro")
            }
            if (providers.gradleProperty("cloudSyncProbe").orNull == "true") {
                proguardFiles("cloud-sync-probe-rules.pro")
            }
            if (providers.gradleProperty("updateProbe").orNull == "true") {
                proguardFiles("update-probe-rules.pro")
            }

            buildConfigField("String", "BUILD_TIME", "\"${getBuildTime(useLastCommitTime = false)}\"")
        }
        create("benchmark") {
            initWith(release)

            // Profile generation needs source names; timing runs keep release optimizations.
            val generatingProfile = providers.gradleProperty("profileGeneration").orNull == "true"
            isMinifyEnabled = !generatingProfile
            isShrinkResources = !generatingProfile
            isDebuggable = false
            isProfileable = true
            versionNameSuffix = "-benchmark"
            applicationIdSuffix = ".benchmark"

            signingConfig = debug.signingConfig

            matchingFallbacks.addAll(commonMatchingFallbacks)
        }
    }

    sourceSets {
        if (providers.gradleProperty("remoteBackupProbe").orNull == "true") {
            getByName("androidTest").java.setSrcDirs(listOf("src/remoteBackupProbe/java"))
        } else if (providers.gradleProperty("cloudSyncProbe").orNull == "true") {
            getByName("androidTest").java.setSrcDirs(listOf("src/androidTest/java/eu/kanade/tachiyomi/cloud"))
            getByName("preview").java.srcDir("src/readerProbe/java")
            getByName("preview").manifest.srcFile("src/readerProbe/AndroidManifest.xml")
        }
        getByName("main").res.setSrcDirs(emptyList<String>())
        getByName("preview").res.srcDirs("src/debug/res")
        getByName("benchmark").res.srcDirs("src/debug/res")
        if (providers.gradleProperty("updateProbe").orNull == "true") {
            getByName("preview").java.srcDir("src/updateProbe/java")
        }
    }
    if (providers.gradleProperty("downloadProbe").orNull == "true" ||
        providers.gradleProperty("rinProbe").orNull == "true" ||
        providers.gradleProperty("remoteBackupProbe").orNull == "true" ||
        providers.gradleProperty("cloudSyncProbe").orNull == "true" ||
        providers.gradleProperty("telegramProbe").orNull == "true" ||
        providers.gradleProperty("updateProbe").orNull == "true"
    ) {
        testBuildType = "preview"
    }

    splits {
        abi {
            isEnable = true
            isUniversalApk = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    packaging {
        jniLibs {
            keepDebugSymbols += listOf(
                "libandroidx.graphics.path",
                "libarchive-jni",
                "libavcodec",
                "libavdevice",
                "libavfilter",
                "libavformat",
                "libavutil",
                "libass",
                "libdav1d",
                "libfreetype",
                "libfribidi",
                "libharfbuzz",
                "libplacebo",
                "libunibreak",
                "libconscrypt_jni",
                "libc++_shared",
                "libffmpegkit_abidetect",
                "libffmpegkit",
                "libimagedecoder",
                "liblibrary",
                "libmpv",
                "libplayer",
                "libpostproc",
                "libquickjs",
                "libsqlite3x",
                "libswresample",
                "libswscale",
                "libtorrserver",
                "libxml2",
                "libnyanime_source_exchange",
                "libmediapipe_tasks_jni",
                "libimage_processing_util_jni",
                "libsurface_util_jni",
            )
                .map { "**/$it.so" }
        }
        resources {
            // Both Kuromoji modules include their contributors; retain both notices.
            merges += setOf("META-INF/CONTRIBUTORS.md", "META-INF/LICENSE.md", "META-INF/NOTICE.md")
            excludes += setOf(
                "kotlin-tooling-metadata.json",
                "LICENSE.txt",
                "META-INF/**/*.properties",
                "META-INF/**/LICENSE.txt",
                "META-INF/*.properties",
                "META-INF/*.version",
                "META-INF/**/*.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/NOTICE",
                "META-INF/README.md",
            )
        }
    }

    dependenciesInfo {
        includeInApk = Config.includeDependencyInfo
        includeInBundle = Config.includeDependencyInfo
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true

        // Disable some unused things
        aidl = false
        renderScript = false
        shaders = false
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            "-opt-in=coil3.annotation.ExperimentalCoilApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
            "-opt-in=kotlinx.coroutines.InternalCoroutinesApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}

val verifyTelegramNativeInputs by tasks.registering {
    val engines = rootProject.file(".local/telegram-native/jniLibs")
    inputs.dir(engines)
    doLast {
        listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64").forEach { abi ->
            val library = engines.resolve("$abi/libtdjsonjava.so")
            val provenance = engines.resolve("$abi/provenance.json")
            check(library.isFile && library.length() > 1_000_000 && provenance.isFile) {
                "Missing official Telegram engine for $abi; run the Telegram native engine workflow"
            }
            val metadata = provenance.readText()
            check(
                metadata.contains("42e6a5259551178d1dab54a22ad96d14bd906e20") &&
                    metadata.contains("d0ca66a1abe52545f14eca635c648932fcde5615") &&
                    metadata.contains(abi),
            ) {
                "Unexpected Telegram native engine provenance"
            }
        }
    }
}
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("NativeLibs")) dependsOn(verifyTelegramNativeInputs)
}

// Artwork is an optional test-only JAR, so renders never add pictures to application assets.
val nyanimePreviewArtwork by tasks.registering(Jar::class) {
    archiveFileName.set("nyanime-preview-artwork.jar")
    destinationDirectory.set(layout.buildDirectory.dir("screenshot-artwork"))
    providers.environmentVariable("NYANIME_PREVIEW_ARTWORK").orNull?.let { directory ->
        from(directory) {
            include("poster-*.jpg", "backdrop.jpg")
            into("nyanime-preview")
        }
    }
}

dependencies {
    implementation(projects.core.attention)
    implementation(projects.core.sourceStudio)
    implementation(projects.core.sourceExchange)
    implementation(projects.core.syncplay)
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    implementation(libs.androidx.webkit)
    implementation(androidx.camera.view)
    implementation(project(":presentation-player"))
    implementation(project(":feature:secret-game"))
    add("screenshotTestImplementation", files(nyanimePreviewArtwork))
    add("screenshotTestImplementation", "com.android.tools.screenshot:screenshot-validation-api:0.0.1-alpha15")
    add("screenshotTestImplementation", "io.coil-kt.coil3:coil-test:3.1.0")
    implementation(projects.i18n)
    implementation(projects.i18nAniyomi)
    implementation(projects.core.archive)
    implementation(projects.core.common)
    implementation(projects.core.panels)
    implementation(projects.core.readerQuality)
    implementation(projects.core.roomVoice)
    implementation(projects.core.telegram)
    implementation(projects.core.privacyDisplay)
    implementation(projects.coreMetadata)
    implementation(projects.sourceApi)
    implementation(projects.newsApi)
    implementation(projects.tvApi)
    implementation(projects.addonApi)
    implementation(projects.sourceLocal)
    implementation(projects.data)
    implementation(projects.domain)
    implementation(projects.presentationCore)
    implementation(projects.presentationWidget)

    // Compose
    implementation(compose.activity)
    implementation(compose.foundation)
    implementation(compose.material3.core)
    implementation(compose.material.icons)
    implementation(compose.animation)
    implementation(compose.animation.graphics)
    debugImplementation(compose.ui.tooling)
    implementation(compose.ui.tooling.preview)
    implementation(compose.ui.util)
    implementation(compose.haze)

    implementation(androidx.interpolator)

    implementation(androidx.paging.runtime)
    implementation(androidx.paging.compose)

    implementation(libs.bundles.sqlite)

    implementation(kotlinx.reflect)
    implementation(kotlinx.immutables)

    implementation(platform(kotlinx.coroutines.bom))
    implementation(kotlinx.bundles.coroutines)

    // AndroidX libraries
    implementation(androidx.annotation)
    implementation(androidx.appcompat)
    implementation(androidx.biometricktx)
    implementation(androidx.constraintlayout)
    implementation(aniyomilibs.compose.constraintlayout)
    implementation(androidx.corektx)
    implementation(androidx.localbroadcastmanager)
    implementation(androidx.splashscreen)
    implementation(androidx.recyclerview)
    implementation(androidx.viewpager)
    implementation(androidx.profileinstaller)
    implementation(aniyomilibs.mediasession)

    implementation(androidx.bundles.lifecycle)

    // Job scheduling
    implementation(androidx.workmanager)
    // Keep NyanimeB's persisted background-work schema and Room runtime after removing optional clients.
    implementation(androidx.room.runtime)

    // RxJava
    implementation(libs.rxjava)

    // Networking
    implementation(libs.conscrypt.android) // Preserve Android 8/9 TLS support.
    implementation(libs.bundles.okhttp)
    implementation(libs.okio)
    implementation(libs.cloud.scanner)

    implementation("com.android.tools.build:apksig:8.9.1")

    // Data serialization (JSON, protobuf, xml)
    implementation(kotlinx.bundles.serialization)

    // HTML parser
    implementation(libs.jsoup)

    // Disk
    implementation(libs.disklrucache)
    implementation(libs.unifile)

    // Preferences
    implementation(libs.preferencektx)

    // Dependency injection
    implementation(libs.injekt)

    // Image loading
    implementation(platform(libs.coil.bom))
    implementation(libs.bundles.coil)
    implementation(libs.subsamplingscaleimageview) {
        exclude(module = "image-decoder")
    }
    implementation(libs.image.decoder)

    // UI libraries
    implementation(libs.material)
    implementation(libs.flexible.adapter.core)
    implementation(libs.photoview)
    implementation(libs.directionalviewpager) {
        exclude(group = "androidx.viewpager", module = "viewpager")
    }
    implementation(libs.insetter)
    implementation(libs.bundles.richtext)
    implementation(libs.aboutLibraries.compose)
    implementation(libs.aboutLibraries.core)
    implementation(libs.bundles.voyager)
    implementation(libs.compose.materialmotion)
    implementation(libs.swipe)
    implementation(libs.compose.webview)
    implementation(libs.compose.grid)
    implementation(libs.reorderable)

    // Logging
    implementation(libs.logcat)

    // Shizuku
    implementation(libs.bundles.shizuku)

    // Tests
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    androidTestImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.sqldelight.jvm.driver)

    // For detecting memory leaks; see https://square.github.io/leakcanary/
    // debugImplementation(libs.leakcanary.android)

    implementation(libs.leakcanary.plumber)

    testImplementation(kotlinx.coroutines.test)

    // mpv-android
    implementation(aniyomilibs.aniyomi.mpv)
    // FFmpeg-kit
    implementation(aniyomilibs.ffmpeg.kit)
    implementation("androidx.media3:media3-transformer:1.8.0")
    implementation("androidx.media3:media3-effect:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
    implementation(aniyomilibs.arthenica.smartexceptions)
    // TorrServer
    implementation(aniyomilibs.torrserver)
    implementation(aniyomilibs.nanohttpd)
    implementation(libs.cast.framework)
    implementation(libs.media.router)
    implementation(libs.watch.secp256k1)
    implementation(libs.watch.qr)
    implementation(libs.watch.secp256k1.android)
    testRuntimeOnly(libs.watch.secp256k1.jvm)
    // seeker seek bar
    implementation(aniyomilibs.seeker)
    // true type parser
    implementation(aniyomilibs.truetypeparser)
}

androidComponents {
    onVariants {
        it.sources.res?.addGeneratedSourceDirectory(englishFallback, EnglishFallbackResourcesTask::outputDirectory)
    }
    onVariants(selector().withFlavor("default" to "standard")) {
        // Only excluding in standard flavor because this breaks
        // Layout Inspector's Compose tree
        it.packaging.resources.excludes.add("META-INF/*.version")
    }
}

buildscript {
    dependencies {
        classpath(kotlinx.gradle)
    }
}

providers.gradleProperty("rinProbeAssets").orNull?.let { probeAssets ->
    extensions.getByType<com.android.build.api.dsl.ApplicationExtension>().sourceSets.getByName(
        "androidTest",
    ).assets.srcDir(probeAssets)
}

// The reviewed history ships with the app: opening Info never waits for a network request.
val prepareUpdateHistory by tasks.registering(GeneratedProjectAssets::class) {
    generatedDirectory.set(layout.buildDirectory.dir("generated/updateHistory"))
    from(rootProject.file("CHANGELOG.md"))
    into(generatedDirectory.dir("raw"))
    rename { "nyanime_update_history.md" }
}
androidComponents.onVariants { variant ->
    variant.sources.res?.addGeneratedSourceDirectory(prepareUpdateHistory) { it.generatedDirectory }
}
tasks.named("preBuild") { dependsOn(prepareUpdateHistory) }

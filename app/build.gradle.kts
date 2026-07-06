import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.roborazzi)
}

@DisableCachingByDefault(because = "Checks optional local FFmpeg artifacts.")
abstract class CheckMedia3FfmpegDsdTask : DefaultTask() {
    @get:Input
    abstract val ffmpegSource: Property<String>

    @get:Input
    abstract val localAarPath: Property<String>

    @get:Input
    abstract val generatedAarPath: Property<String>

    @get:Input
    abstract val localJniPath: Property<String>

    @TaskAction
    fun checkConfiguredArtifact() {
        when (val source = ffmpegSource.get()) {
            "localaar", "local-aar", "local_aar" -> {
                val file = File(localAarPath.get())
                if (!file.exists()) missingArtifact("localAar", file)
            }

            "generatedaar", "generated-aar", "generated_aar" -> {
                val file = File(generatedAarPath.get())
                if (!file.exists()) missingArtifact("generatedAar", file)
            }

            "project" -> {
                val file = File(localJniPath.get())
                if (!file.exists()) missingArtifact("project", file)
            }

            "fallback", "official", "jellyfin" -> {
                logger.warn(
                    """
                    |
                    | *** libffmpegJNI with dsd_lsbf is disabled.
                    | *** Current mica.ffmpegSource=$source.
                    | *** DSF playback will use the fallback FFmpeg dependency.
                    |
                    """.trimMargin(),
                )
            }

            else -> unsupportedSource(source)
        }
    }

    private fun missingArtifact(source: String, file: File): Nothing =
        throw GradleException(
            """
            mica.ffmpegSource=$source, but the configured artifact was not found.

            Expected:
              ${file.absolutePath}

            Run:
              .\scripts\build-media3-ffmpeg-dsd.ps1

            Or switch to:
              mica.ffmpegSource=fallback
            """.trimIndent(),
        )

    private fun unsupportedSource(source: String): Nothing =
        throw GradleException(
            """
            Unsupported mica.ffmpegSource: $source

            Supported values:
              - fallback
              - localAar
              - generatedAar
              - project
            """.trimIndent(),
        )
}

providers.gradleProperty("mica.alternateBuildDir").orNull?.let { alternateDir ->
    layout.buildDirectory.set(file(alternateDir))
}

val qaSideBySide = providers.gradleProperty("mica.qaSideBySide")
    .map(String::toBoolean)
    .getOrElse(false)

val selectedFfmpegSource = providers.gradleProperty("mica.ffmpegSource")
    .map(String::trim)
    .map(String::lowercase)
    .getOrElse("fallback")

val media3FfmpegLocalAarPath = file("libs/media3-ffmpeg-decoder-dsd.aar").absolutePath

val media3FfmpegGeneratedAarPath = layout.buildDirectory
    .file("generated/media3-ffmpeg/media3-ffmpeg-decoder-dsd.aar")
    .get()
    .asFile
    .absolutePath

val media3FfmpegLocalJniPath = rootProject
    .file("third_party/media3-ffmpeg-decoder/src/main/jniLibs/arm64-v8a/libffmpegJNI.so")
    .absolutePath

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use(::load)
    }
}

fun readReleaseSigningEnv(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() }

android {
    namespace = "com.mica.music"
    compileSdk = 37

    defaultConfig {
        applicationId = if (qaSideBySide) "com.mica.music.qa" else "com.mica.music"
        minSdk = 26
        targetSdk = 37
        versionCode = 32
        versionName = "0.2.0" + if (qaSideBySide) "-qa" else ""

        ndk {
            // 仅 64 位真机；自编 FFmpeg 也只编 arm64-v8a
            abiFilters += listOf("arm64-v8a")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    signingConfigs {
        create("release") {
            val ciKeystoreFile = readReleaseSigningEnv("MICA_KEYSTORE_FILE")?.let(::file)

            when {
                ciKeystoreFile?.exists() == true -> {
                    storeFile = ciKeystoreFile
                    storePassword = readReleaseSigningEnv("MICA_KEYSTORE_PASSWORD")
                    keyAlias = readReleaseSigningEnv("MICA_KEY_ALIAS")
                    keyPassword = readReleaseSigningEnv("MICA_KEY_PASSWORD")
                }

                keystorePropertiesFile.exists() -> {
                    storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                    storePassword = keystoreProperties.getProperty("storePassword")
                    keyAlias = keystoreProperties.getProperty("keyAlias")
                    keyPassword = keystoreProperties.getProperty("keyPassword")
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )

            signingConfigs.findByName("release")
                ?.takeIf { it.storeFile?.exists() == true }
                ?.let { signingConfig = it }
        }

        create("perf") {
            initWith(getByName("release"))
            isDebuggable = qaSideBySide
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }

        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Kotlin 2.2 + lifecycle lint 2.9.x 分析 API 不兼容时会崩溃（NonNullableMutableLiveDataDetector）
    lint {
        disable += "NullSafeMutableLiveData"
    }
}

ksp {
    arg("room.schemaLocation", file("schemas").absolutePath)
}

roborazzi {
    outputDir.set(file("src/test/snapshots"))
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.coil.compose)
    implementation(libs.androidx.palette.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.session)

    when (selectedFfmpegSource) {
        "localaar", "local-aar", "local_aar" -> {
            implementation(files(media3FfmpegLocalAarPath))
        }

        "generatedaar", "generated-aar", "generated_aar" -> {
            implementation(files(media3FfmpegGeneratedAarPath))
        }

        "project" -> {
            implementation(project(":media3-ffmpeg-decoder-dsd"))
        }

        "fallback", "official", "jellyfin" -> {
            logger.warn(
                """
                |
                | *** Using fallback FFmpeg dependency.
                | *** DSD-enabled Media3 FFmpeg is disabled.
                | *** To enable it, set one of:
                | ***   mica.ffmpegSource=localAar
                | ***   mica.ffmpegSource=generatedAar
                | ***   mica.ffmpegSource=project
                |
                """.trimMargin(),
            )

            implementation(libs.androidx.media3.exoplayer.ffmpeg)
        }

        else -> {
            throw GradleException(
                """
                Unsupported mica.ffmpegSource: $selectedFfmpegSource

                Supported values:
                  - fallback
                  - localAar
                  - generatedAar
                  - project
                """.trimIndent(),
            )
        }
    }

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.reorderable)
    implementation(libs.kyant.taglib)
    implementation(libs.jaudiotagger)
    implementation(libs.blurview)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
}

val checkMedia3FfmpegDsd = tasks.register("checkMedia3FfmpegDsd", CheckMedia3FfmpegDsdTask::class) {
    group = "verification"
    description = "Checks whether the configured DSD-enabled Media3 FFmpeg artifact exists."

    ffmpegSource.set(selectedFfmpegSource)
    localAarPath.set(media3FfmpegLocalAarPath)
    generatedAarPath.set(media3FfmpegGeneratedAarPath)
    localJniPath.set(media3FfmpegLocalJniPath)
}

tasks.named("preBuild") {
    dependsOn(checkMedia3FfmpegDsd)
}

tasks.register("micaCheck") {
    group = "verification"
    description = "Runs Mica's compile, lint, JVM/Robolectric, and screenshot regression gates."

    dependsOn(
        "compileDebugKotlin",
        "lintDebug",
        "testDebugUnitTest",
        "verifyRoborazziDebug",
    )
}

tasks.register("micaScreenshotFull") {
    group = "verification"
    description = "Runs the complete Roborazzi screenshot regression matrix."

    dependsOn("verifyRoborazziDebug")
}

tasks.register("micaRecordScreenshotFull") {
    group = "verification"
    description = "Records the complete Roborazzi screenshot regression matrix."

    dependsOn("recordRoborazziDebug")
}

val nightlyRequested = gradle.startParameter.taskNames.any {
    it.substringAfterLast(':') == "micaNightlyCheck"
}

val fullScreenshotsRequested = nightlyRequested || gradle.startParameter.taskNames.any {
    it.substringAfterLast(':') in setOf("micaScreenshotFull", "micaRecordScreenshotFull")
}

tasks.withType<Test>().configureEach {
    systemProperty("mica.nightly", nightlyRequested.toString())
    systemProperty("mica.fullScreenshots", fullScreenshotsRequested.toString())
    systemProperty("mica.screenshotGolden", fullScreenshotsRequested.toString())
}

tasks.register("micaNightlyCheck") {
    group = "verification"
    description = "Runs compile, lint, all JVM/Robolectric tests, full screenshots, and nightly fuzzing."

    dependsOn(
        "micaCheck",
        "micaScreenshotFull",
    )
}

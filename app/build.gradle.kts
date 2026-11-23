plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}


/** KEY=value pairs from the project's .env (gitignored; template in .env.dist). */
val dotEnv: Map<String, String> = rootProject.file(".env").takeIf { it.exists() }?.readLines().orEmpty()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
    .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim().removeSurrounding("\"") }

/** A setting from the environment (CI) or from .env (local). */
fun setting(key: String): String? = System.getenv(key)?.ifBlank { null } ?: dotEnv[key]?.ifBlank { null }

/** Output of a git command in the project, or null (no git, e.g. a source download). */
fun git(vararg args: String): String? = runCatching {
    providers.exec { commandLine("git", *args); workingDir = rootDir; isIgnoreExitValue = true }
        .standardOutput.asText.get().trim().ifEmpty { null }
}.getOrNull()

/** Release signing key; only present on the release pipeline and on machines that have it. */
val releaseKeystore: File? = setting("MASAL_KEYSTORE_FILE")?.let { rootProject.file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.alike.masal"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.alike.masal"
        minSdk = 26
        targetSdk = 35
        // Version code: the number of commits, locally and on the release pipeline alike, so the
        // GitHub release of a commit and a local build of it match, and a newer commit always
        // installs over an older one. Version name: from the tag (v1.2.3 -> 1.2.3) on the pipeline,
        // git describe locally.
        versionCode = setting("MASAL_VERSION_CODE")?.toIntOrNull() ?: git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1
        versionName = setting("MASAL_VERSION_NAME") ?: git("describe", "--tags", "--always")?.removePrefix("v") ?: "0.1.0"
    }
    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = releaseKeystore
            storePassword = setting("MASAL_KEYSTORE_PASSWORD")
            keyAlias = setting("MASAL_KEY_ALIAS")
            keyPassword = setting("MASAL_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipe Extractor uses java.nio APIs that Android has only from API 33.
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // UI tests run on the JVM with Robolectric (no TV or emulator needed); screenshots via Roborazzi.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("roborazzi.test.record", "true")
                it.systemProperty("roborazzi.record.filePathStrategy", "relativePathFromRoborazziContextOutputDirectory")
                it.maxHeapSize = "2g"
                // Local values for tests (e.g. MASAL_TEST_HOME_GROUP) come from the gitignored .env.
                dotEnv.forEach { (k, v) -> it.environment(k, v) }
            }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.media3:media3-exoplayer:1.5.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.0")
    implementation("androidx.media3:media3-ui:1.5.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // YouTube trailer streams (seerr.md); GPL-3.0, which is why Masal is GPL-3.0-or-later.
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.27.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    testImplementation(composeBom)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties
import java.util.TimeZone

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing — SAME key as the watch app (Wear Data Layer needs a matching
// signature; Play uses one signing key per listing). See ../wearvian/docs/play-store-packaging.md.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}

// Stamped into BuildConfig.BUILD_TIME so the on-device version label changes on
// every build — lets you confirm at a glance whether the installed APK is current.
val buildTime: String = SimpleDateFormat("yyyy-MM-dd HH:mm").apply {
    timeZone = TimeZone.getTimeZone("America/New_York")
}.format(Date())

// Current git branch, stamped into BuildConfig.GIT_BRANCH so a non-main build is obvious on-device.
// Empty if git isn't available; the UI only shows it when it's not "main".
val gitBranch: String = runCatching {
    val p = ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
}.getOrDefault("")

android {
    namespace = "org.fivesevenfive.wearvian.companion"
    compileSdk = 35

    defaultConfig {
        // MUST match the watch app's applicationId: the Wear Data Layer delivers
        // messages only between apps sharing the same (applicationId, signature).
        // The code namespace stays distinct (org.fivesevenfive.wearvian.companion).
        applicationId = "org.fivesevenfive.wearvian"
        minSdk = 26
        targetSdk = 35       // Play requires new apps to target API 35+
        // versionCode lanes under the shared package: 1xxx = Wear, 2xxx = phone.
        // Must stay unique across BOTH apps and only ever increase.
        versionCode = 2014
        versionName = "0.7.0"
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
        buildConfigField("String", "GIT_BRANCH", "\"$gitBranch\"")
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 shrink + obfuscate: renames classes/methods so the reverse-engineered Rivian
            // cloud/GraphQL protocol isn't trivially readable from the shipped APK (wire calls
            // unchanged). A courtesy to Rivian, matching the watch app — not hardened DRM.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystorePropertiesFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Debug builds get a "- debug" version suffix so a debug build uploaded to Play
            // (Internal App Sharing) is unmistakable from a real release on-device and in the console.
            versionNameSuffix = " - debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // We use ComponentActivity + activity-compose (no Fragments); this lintVital check flags
        // a transitive androidx.fragment version we never use. False positive — don't fail release.
        disable += "InvalidFragmentVersionForActivityResult"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Jetpack Compose (phone Material3)
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Wear OS Data Layer (phone side) — hand enrollment results to the watch
    implementation("com.google.android.gms:play-services-wearable:18.2.0")

    // QR scanning for the HA-key import flow — GMS Code Scanner (Play Services UI,
    // no CameraX, no camera permission; the scanner module is fetched on first use).
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    // Encrypted on-device storage for session tokens (monthly re-auth reuse)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Rivian cloud calls (login / MFA / getUserInfo / EnrollPhone)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    testImplementation("junit:junit:4.13.2")
}

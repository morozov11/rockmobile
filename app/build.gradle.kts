plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

import java.util.Properties

android {
    namespace = "com.rockmobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rockmobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.1.6"
        buildConfigField("String", "BUILD_REVISION", "\"${providers.exec { commandLine("git", "rev-parse", "--short=7", "HEAD") }.standardOutput.asText.get().trim()}\"")
        buildConfigField("String", "DEBUG_ROCKSERVER_URL", "\"\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val keystoreProperties = Properties()
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
    }

    signingConfigs {
        create("release") {
            val storePath = keystoreProperties.getProperty("storeFile")
            check(!storePath.isNullOrBlank()) {
                "Missing keystore.properties — create it to build a signed release APK"
            }
            storeFile = rootProject.file(storePath)
            storePassword = keystoreProperties.getProperty("storePassword")
            keyAlias = keystoreProperties.getProperty("keyAlias")
            keyPassword = keystoreProperties.getProperty("keyPassword")
        }
    }

    buildFeatures { compose = true; buildConfig = true }
    buildTypes {
        debug {
            // Same cert as release so Digital Asset Links / passkeys match assetlinks.json.
            signingConfig = signingConfigs.getByName("release")
            val debugServerUrl = providers.gradleProperty("rockmobileDevServerUrl").orNull?.trim()?.trimEnd('/').orEmpty()
            require(debugServerUrl.isEmpty() || debugServerUrl.startsWith("https://")) {
                "rockmobileDevServerUrl must use HTTPS and must not include credentials"
            }
            buildConfigField("String", "DEBUG_ROCKSERVER_URL", "\"$debugServerUrl\"")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation("androidx.media3:media3-common:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    // Android's platform org.json methods are stubs in local JVM tests.
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

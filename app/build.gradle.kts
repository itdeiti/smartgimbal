plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.smartgimbal"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.smartgimbal"
        // Pixel 7 ships with Android 13 (API 33); this lets us use the API 33 BLE write/notify calls.
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // Only 64-bit ARM (Pixel 7 and every current phone). Vosk and ML Kit otherwise ship native
        // libraries for seven ABIs, which made the APK 166 MB.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // BuildConfig.DEBUG gates logcat output and the raw frame sender.
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

// The Vosk model is bundled into the APK but not kept in git (~95 MB); fail early with a hint.
val checkVoskModel by tasks.registering {
    val marker = layout.projectDirectory.file("src/main/assets/model-de/uuid").asFile
    doLast {
        if (!marker.exists()) {
            throw GradleException("Vosk-Modell fehlt. Einmal ausführen: scripts/fetch-vosk-model.sh")
        }
    }
}
tasks.named("preBuild") { dependsOn(checkVoskModel) }

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.face.detection)
    implementation(libs.vosk.android)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}

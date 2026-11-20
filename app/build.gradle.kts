import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// -Pqnn=true builds against ONNX Runtime's QNN package (Snapdragon Hexagon NPU).
val qnn = (findProperty("qnn") as String?) == "true"

android {
    namespace = "app.relens"
    compileSdk { version = release(37) { minorApiLevel = 2 } }

    defaultConfig {
        // Vintage Snap (the code keeps its first package, app.relens, as its namespace)
        applicationId = "app.vintagesnap"
        minSdk = 29          // Android 10: GLES 3.1 compute, Camera2 RAW everywhere
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"
        ndk { abiFilters += listOf("arm64-v8a") }
        buildConfigField("boolean", "QNN", qnn.toString())
    }

    // Release signing: a keystore kept off the repo. Its properties file (storeFile, storePassword,
    // keyAlias, keyPassword) is found by -PvsSigning=<path> or at the default place on Mike 1;
    // without one, release builds fall back to the debug key (for testing).
    val signingProps = File((findProperty("vsSigning") as String?) ?: "/Volumes/Mike 1/vintage_snap_release.properties")
    signingConfigs {
        if (signingProps.exists()) create("vs") {
            val p = Properties().apply { signingProps.inputStream().use { load(it) } }
            storeFile = File(p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias")
            keyPassword = p.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("vs") ?: signingConfigs.getByName("debug")
        }
    }
    buildFeatures { buildConfig = true; compose = true }
    packaging { jniLibs { useLegacyPackaging = true } }   // QNN loads its HTP libs from the lib dir
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources {
        noCompress += listOf("tflite", "onnx")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.11.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.1")
    // CameraX: preview, RAW (DNG) + JPEG capture, tap-to-focus; Camera2 interop for capture metadata.
    val camerax = "1.7.0"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-compose:$camerax")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    // ARCore (optional on the phone): pose tracking for Parallax capture, Raw Depth as a check.
    implementation("com.google.ar:core:1.57.0")

    // LiteRT (TFLite) 1.x: the classic Interpreter API and its GPU delegate.
    implementation("com.google.ai.edge.litert:litert:1.4.2")
    implementation("com.google.ai.edge.litert:litert-gpu:1.4.2")
    implementation("com.google.ai.edge.litert:litert-gpu-api:1.4.2")
    // ONNX Runtime Mobile: CPU, XNNPACK and NNAPI execution providers.
    if (qnn) implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.29.0")
    else implementation("com.microsoft.onnxruntime:onnxruntime-android:1.32.0")
}

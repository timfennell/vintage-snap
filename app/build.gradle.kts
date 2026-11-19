plugins {
    id("com.android.application")
}

// -Pqnn=true builds against ONNX Runtime's QNN package (Snapdragon Hexagon NPU).
val qnn = (findProperty("qnn") as String?) == "true"

android {
    namespace = "app.relens.spike"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.relens.spike"
        minSdk = 29          // Android 10: GLES 3.1 compute, Camera2 RAW everywhere
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-spike"
        ndk { abiFilters += listOf("arm64-v8a") }
        buildConfigField("boolean", "QNN", qnn.toString())
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // The spike is benchmarked as release (no debugger overhead), signed with the debug key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures { buildConfig = true }
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
    // LiteRT (TFLite) 1.x: the classic Interpreter API and its GPU delegate.
    implementation("com.google.ai.edge.litert:litert:1.4.2")
    implementation("com.google.ai.edge.litert:litert-gpu:1.4.2")
    implementation("com.google.ai.edge.litert:litert-gpu-api:1.4.2")
    // ONNX Runtime Mobile: CPU, XNNPACK and NNAPI execution providers.
    if (qnn) implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.29.0")
    else implementation("com.microsoft.onnxruntime:onnxruntime-android:1.32.0")
}

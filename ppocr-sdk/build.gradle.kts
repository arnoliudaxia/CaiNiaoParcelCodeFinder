plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.paddle.ocr"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            consumerProguardFiles("proguard-rules.pro")
        }
    }
    packaging {
        jniLibs {
            // QNN HTP skel libs must exist as real files for the DSP (FastRPC) loader.
            useLegacyPackaging = true
            excludes += listOf("**/libQnnDsp*.so", "**/libQnnGpu.so")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    // ORT build with the Qualcomm QNN EP (pulls com.qualcomm.qti:qnn-runtime).
    // Note: this package does not include NNAPI/XNNPACK EPs.
    implementation(libs.onnxruntime.android.qnn)
    implementation(libs.opencv.android)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit.ktx)
    androidTestImplementation(libs.androidx.test.monitor)
    androidTestImplementation(libs.junit)
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jipaiqi.doudizhu"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jipaiqi.doudizhu"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Ship native libs for the ABIs where the ORIGINAL prebuilt
        // `libyolov8ncnn.so` actually exists: arm64-v8a + armeabi-v7a.
        // x86_64 emulator builds don't have a NCNN YOLO native library
        // from the original APK, so exclude x86_64 to avoid UnsatisfiedLinkError
        // at runtime (the detector would fall back to pure OCR anyway).
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
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
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // Required because <application android:extractNativeLibs="true"> is set in
            // AndroidManifest.xml.  Without this flag AGP 8+ would strip the
            // embedded .so copies to save space but some Android 12 Samsung/
            // Xiaomi launchers refuse to dlopen() a library mapped directly out
            // of the APK ZIP — symptom: "tap icon → splash → immediate crash"
            // with UnsatisfiedLinkError even though `unzip -l` shows the .so
            // files are present.  Using legacy packaging copies them into
            // /data/app/.../lib/ at install time which is 100% reliable.
            useLegacyPackaging = true
        }
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties"
            )
        }
    }
}

dependencies {
    // AndroidX core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.fragment:fragment-ktx:1.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-service:2.8.3")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // On-device inference: ONNX Runtime Android.
    // This is what runs the converted DouZero + YOLO models.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.1")

    // OCR: ML Kit bundled text recognition (Latin). Works without Play Services
    // because the model is bundled inside the AAR. We use it to recognize the
    // corner numbers/letters on cards (3..10, J, Q, K, A, 2) as a fallback /
    // cross-check for the YOLO detector.
    implementation("com.google.mlkit:text-recognition:16.0.0")

    // Lightweight image handling for preprocessing before OCR/YOLO.
    implementation("androidx.camera:camera-core:1.3.4")

    // Unit tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

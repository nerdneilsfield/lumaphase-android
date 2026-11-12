import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.lumaphase.android"
    compileSdk = 36

    // Pinned NDK: this version provides <EGL/eglext.h> with the frame timestamp tokens and
    // builds the single C bridge. No third-party code and no download step.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "dev.lumaphase.android"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    // Each device needs only its own OpenCV/JNI ABI, not all four native binaries.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = false
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
    }

    sourceSets {
        // The shared CR-001 placement fixture is read in place instead of being copied, so
        // the test and the other repositories use the exact same bytes.
        getByName("test").resources.srcDir("../docs/cw/fixtures")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Pinned local OpenCV 5.1.0 AAR; no Maven coordinate and no build-time download.
    implementation(files("../third_party/opencv-5.1.0/opencv-5.1.0.aar"))

    // The same Gson artifact was already a cached test dependency; it is promoted to the
    // runtime classpath so the layout import parser can be exercised in host JVM tests
    // instead of relying on Android's JsonReader, which cannot run here.
    implementation("com.google.code.gson:gson:2.13.2")

    testImplementation("junit:junit:4.13.2")
}

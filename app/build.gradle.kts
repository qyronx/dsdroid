plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.dsdroid"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.dsdroid"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        release {
            // 디버그 빌드는 debuggable 이라 JNI/오디오 경로가 느리다. 릴리스로 빌드하되
            // 바로 설치할 수 있도록 디버그 키로 서명한다.
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    lint {
        // assembleRelease 에서 lintVital 단계가 빌드를 느리게/실패시키지 않도록
        checkReleaseBuilds = false
        abortOnError = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        // 코어(.so)를 dlopen 으로 불러오므로 압축 해제 방식으로 패키징
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("com.github.Swordfish90:LibretroDroid:0.14.0")
}

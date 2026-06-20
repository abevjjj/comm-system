plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.shai.commsystem"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.shai.commsystem"
        minSdk = 21        // Android 5.0，符合需求 3(a)
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
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
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")

    // WebSocket 客户端：OkHttp 自带 WebSocket 支持，成熟稳定，minSdk 21 兼容
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 协程：简化异步网络请求代码
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // JSON 解析：org.json 已内置于 Android SDK，无需额外依赖
}

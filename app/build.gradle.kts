plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.aiphone.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aiphone.assistant"
        minSdk = 28          // Android 9，与 MAA-Meow 一致
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    /**
     * 调试签名用工程自带的密钥，不用默认的 ~/.android/debug.keystore。
     *
     * 两个原因：
     *   1. **可移植**：默认位置在用户家目录里，换台机器 / 重新 clone
     *      就没有，AGP 会尝试现场生成。工程自带一份，clone 下来就能构建。
     *   2. **沙箱友好**：AGP 会在那个目录里读写锁文件，而本机开发环境的
     *      文件沙箱不允许写工作区之外，构建会直接失败在
     *      "Unable to create debug keystore ... because it is not writable"。
     *
     * 口令就是 Android 调试密钥的公开默认值（android / androiddebugkey），
     * 这**不是秘密也不能当秘密用** —— 调试密钥只用于本地安装，
     * 绝不能拿它签发布包。
     */
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        aidl = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    debugImplementation(libs.androidx.ui.tooling)
}

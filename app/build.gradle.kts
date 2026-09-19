import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * 发布签名的配置来源。
 *
 * `keystore.properties` 长这样（见 keystore.properties.example）：
 *
 *     storeFile=keystore/release.keystore
 *     storePassword=...
 *     keyAlias=...
 *     keyPassword=...
 *
 * 它和密钥文件都在 .gitignore 里，**永远不要提交**。
 */
val releaseProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

/** 配置齐了、而且密钥文件真的在，才启用发布签名 */
val hasReleaseKey: Boolean = run {
    val path = releaseProps.getProperty("storeFile") ?: return@run false
    releaseProps.getProperty("storePassword") != null &&
        releaseProps.getProperty("keyAlias") != null &&
        releaseProps.getProperty("keyPassword") != null &&
        rootProject.file(path).exists()
}

android {
    namespace = "com.aiphone.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aiphone.assistant"
        minSdk = 28          // Android 9，与 MAA-Meow 一致
        targetSdk = 35
        versionCode = 12
        versionName = "0.5.0"
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

        /**
         * 发布签名。
         *
         * 密钥和口令**不进仓库** —— 从 `keystore.properties` 读，那个文件在
         * .gitignore 里。跑一次 `bash setup_release_keystore.sh` 生成。
         *
         * 文件不存在时**不报错**，只是 release 产物没有签名
         * （出 app-release-unsigned.apk）。这样 clone 下来的人不会因为
         * 缺密钥连构建都过不去 —— 他只是发不了包而已。
         */
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(releaseProps.getProperty("storeFile"))
                storePassword = releaseProps.getProperty("storePassword")
                keyAlias = releaseProps.getProperty("keyAlias")
                keyPassword = releaseProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        buildConfig = true
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

    // Shizuku：让普通应用借到 shell 身份。副屏、screencap、input 都要靠它
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    debugImplementation(libs.androidx.ui.tooling)
}

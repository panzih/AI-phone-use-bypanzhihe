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

/**
 * 自动更新（0.17.0 起**整个功能已删除**，用户要求"删掉所有软件内检查更新的入口"）。
 *
 * 这里不再有 `enableUpdate` 开关，也没有 `BuildConfig.ENABLE_UPDATE` ——
 * UpdateChecker / UpdateDialog / 关于页入口 / 启动自动检查都不在了，
 * 相关权限（REQUEST_INSTALL_PACKAGES）与 FileProvider 的 updates 路径也一并去掉。
 * 版本分发改由外部渠道（GitHub Releases / 更新站网页）负责。
 */

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
        versionCode = 91      // 0.17.1：系统提示词整篇重写（补高风险闸门/上限，修三处误导）
        versionName = "0.17.1"
        // 设备冒烟测试：androidTest 用 AndroidJUnitRunner 跑
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
     * 这**不是秘密也不能当秘密用** —— 私钥就躺在仓库里，谁都能签出同签名的包。
     *
     * 它只用于 `debug` 构建。**发布包必须用正式密钥**，CI 会用 apksigner
     * 校验指纹，发现正式包其实是这把开发者密钥签的就直接失败。
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
         * 文件不存在时**不报错**：正式包会变成 unsigned 产物，用来提醒
         * "这台机器不具备发布条件"。**不会**退回公开的 debug keystore ——
         * 那样出的包任何人都能伪造，不能代表官方发布。
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

    /**
     * 只有一条发布线：`release`，用正式密钥签名。
     *
     * 0.14.1 起取消了 `legacy`（开发者 Debug 签名）兼容线 —— 那把私钥随仓库
     * 公开，任何人都能签出同签名的包来覆盖安装，不能代表官方发布。
     * 旧 legacy 用户需要卸载后安装正式签名包（本地数据会清空）。
     */
    buildTypes {
        release {
            // 启用 R8（优化+裁剪）。proguard-rules.pro 里目前加了 -dontobfuscate，
            // 开发阶段不实际混淆，只做代码裁剪；正式发布时去掉那行即可。
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 没配正式密钥时不签名（产物是 unsigned），**不再退回开发者密钥**。
            // CI 会先还原 Secrets 里的密钥，再用 apksigner 校验；两步缺一步就失败。
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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
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

    // 本地单元测试：JUnit + Robolectric。Robolectric 提供真实 android.graphics.Rect，
    // 让坐标点击的命中测试 bounds.contains 能在 JVM 上真跑（否则是 stub）
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")

    // 设备冒烟测试：需要真机/模拟器，不进入 CI 的 JVM 单测任务
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

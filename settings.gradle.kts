// ============================================================
// 依赖仓库：国内镜像优先，官方源兜底
// ============================================================
//
// 为什么这么排：Gradle 会**按顺序**尝试这些仓库，前一个拿不到才试下一个。
// 把阿里云镜像放在前面、官方源留在后面，效果是：
//
//   网络正常   → 从镜像拿，快
//   镜像没有   → 自动回落到官方源，不会因为镜像缺东西就构建失败
//
// 这不是"必须用镜像"—— 官方源能通的话，慢一点也能装上。
// 但 Shizuku 这类第三方库只发布在 Maven Central，国内直连经常超时，
// 超时的表现是 "Could not resolve dev.rikka.shizuku:api:13.1.5"，
// 看起来像依赖写错了，其实是网络。
pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Google 系（AndroidX / Compose / AGP）的镜像
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        // Maven Central 的镜像 —— Shizuku 就在这里
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        // 官方源兜底
        google()
        mavenCentral()
    }
}

// 工程名（Android Studio 左侧树里显示的那个）。
// 这是品牌名「纸盒」，跟 APK 文件名无关 —— APK 由 :app 模块决定。
rootProject.name = "纸盒"
include(":app")

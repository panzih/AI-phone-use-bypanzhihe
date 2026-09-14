pluginManagement {
    repositories {
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
        google()
        mavenCentral()
    }
}

// 工程名（Android Studio 左侧树里显示的那个）。
// 这是品牌名「纸盒」，跟 APK 文件名无关 —— APK 由 :app 模块决定。
rootProject.name = "纸盒"
include(":app")

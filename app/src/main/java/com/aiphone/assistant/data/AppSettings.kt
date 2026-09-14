package com.aiphone.assistant.data

enum class OperationMode(
    val id: String, val label: String, val available: Boolean, val note: String,
) {
    ACCESSIBILITY(
        id = "accessibility", label = "无障碍", available = true,
        note = "系统级服务，一次授权长期有效，重启手机也不失效。不需要电脑，不需要装别的东西。",
    ),
    ADB(
        id = "adb", label = "ADB / Shizuku", available = false,
        note = "能截到银行/支付类页面（无障碍截不到），但无 root 时每次重启都要重新授权一次。当前版本还没接入。",
    );

    companion object {
        fun fromId(id: String?): OperationMode = entries.firstOrNull { it.id == id } ?: ACCESSIBILITY
    }
}

enum class AutoClear(val minutes: Int, val label: String) {
    NEVER(0, "不自动清空"), M30(30, "超过 30 分钟"), H1(60, "超过 1 小时"),
    H3(180, "超过 3 小时"), H12(720, "超过 12 小时"), D1(1440, "超过 1 天");

    companion object {
        fun fromMinutes(m: Int): AutoClear = entries.firstOrNull { it.minutes == m } ?: H1
    }
}

data class AppSettings(
    val baseUrl: String = "https://api.deepseek.com",
    val apiKey: String = "",
    val modelName: String = "deepseek-flash",
    val detail: String = "original",
    val mode: OperationMode = OperationMode.ACCESSIBILITY,
    val maxSteps: Int = 30,
    val autoClearMinutes: Int = 0,
    val memoryEnabled: Boolean = false,
    val keepMemory: Boolean = true,
    val saveLogs: Boolean = true,
    val saveScreenshots: Boolean = true,
) {
    val maskedApiKey: String
        get() = when {
            apiKey.isBlank() -> "未填写"
            apiKey.length <= 8 -> "****"
            else -> apiKey.take(4) + "****" + apiKey.takeLast(4)
        }
    val autoClear: AutoClear get() = AutoClear.fromMinutes(autoClearMinutes)
}

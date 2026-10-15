package com.aiphone.assistant.data

enum class OperationMode(
    val id: String,
    val label: String,
    val available: Boolean,
    val note: String,
) {
    ACCESSIBILITY(
        id = "accessibility",
        label = "无障碍",
        available = true,
        note = "系统级服务，一次授权长期有效，重启手机也不失效。不需要电脑，不需要装别的东西。",
    ),

    ADB(
        id = "adb",
        label = "ADB / Shizuku",
        available = false,
        note = "暂未完成相关配置",
    );

    companion object {
        fun fromId(id: String?): OperationMode =
            entries.firstOrNull { it.id == id } ?: ACCESSIBILITY
    }
}

enum class ThinkingMode(
    val id: String,
    val label: String,
    val note: String,
    val toggle: String?,
    val effort: String?,
) {
    OFF(
        id = "off",
        label = "OFF",
        note = "不推理，直接给动作。最快最省，适合「点哪个按钮」这类简单判断",
        toggle = "disabled",
        effort = null,
    ),
    LOW(
        id = "low",
        label = "LOW",
        note = "少量推理。速度接近 OFF，复杂界面比 OFF 稳",
        toggle = "enabled",
        effort = "low",
    ),
    HIGH(
        id = "high",
        label = "HIGH",
        note = "充分推理。慢一些、贵一些，绕圈子的任务更靠谱",
        toggle = "enabled",
        effort = "high",
    ),
    MAX(
        id = "max",
        label = "MAX",
        note = "推理拉满。最慢最贵，只在你确认任务确实难的时候用",
        toggle = "enabled",
        effort = "max",
    ),
    DEFAULT(
        id = "default",
        label = "默认",
        note = "什么都不发，由服务端自己决定（DeepSeek 默认 HIGH，Kimi 默认 balance）",
        toggle = null,
        effort = null,
    );

    val thinkingOn: Boolean get() = this == LOW || this == HIGH || this == MAX

    companion object {
        fun fromId(id: String?): ThinkingMode =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

enum class ContextPolicy(
    val id: String,
    val label: String,
    val note: String,
) {
    RESET_EACH_TIME(
        id = "reset",
        label = "每次重置",
        note = "每发一条新任务就开一个全新的上下文，模型不记得上一条任务。每次都带上最新记忆、不会越跑越糊涂，代价是每步都要重新理解界面",
    ),

    H24(
        id = "h24",
        label = "超过 24 小时",
        note = "24 小时没发消息就清空上下文。日常用这一档：一天之内的连续操作能接上（模型记得你说过什么），隔天又是干净的",
    ),

    UNLIMITED(
        id = "never",
        label = "不限",
        note = "永远不清空，模型记得这次会话里的每一句话。最省往返（命中缓存的部分便宜 50 倍），但上下文会一直涨，迟早顶到模型窗口上限",
    );

    val idleMinutes: Int
        get() = when (this) {
            RESET_EACH_TIME -> 0
            H24 -> 24 * 60
            UNLIMITED -> 0
        }

    companion object {
        fun fromId(id: String?): ContextPolicy =
            entries.firstOrNull { it.id == id } ?: H24
    }
}

data class AppSettings(
    val baseUrl: String = "https://api.deepseek.com",
    val apiKey: String = "",
    val modelName: String = "deepseek-flash",
    val useFreeModel: Boolean = false,
    val thinking: ThinkingMode = ThinkingMode.DEFAULT,
    val mode: OperationMode = OperationMode.ACCESSIBILITY,
    val maxSteps: Int = 30,
    val contextPolicy: ContextPolicy = ContextPolicy.H24,
    val memoryEnabled: Boolean = false,
    val saveLogs: Boolean = true,
    val saveScreenshots: Boolean = true,
    val localDialogDismiss: Boolean = true,
) {
    val maskedApiKey: String
        get() = when {
            apiKey.isBlank() -> "未填写"
            apiKey.length <= 8 -> "****"
            else -> apiKey.take(4) + "****" + apiKey.takeLast(4)
        }
}

fun stepsLabel(step: Int, maxSteps: Int): String =
    if (maxSteps <= 0) "第 $step 步（不限）" else "第 $step / $maxSteps 步"

package com.aiphone.assistant.ui

/**
 * 界面路由。
 *
 * 只有三个页面，暂时不引入 Navigation 库 —— 用最简单的状态切换就够了，
 * 少一个依赖少一层复杂度。等页面变多再换。
 */
enum class Screen {
    /** 主界面：中间那块给底层通道（ADB 等）用 */
    CONTROL,

    /** 设置页：模型配置 + 触控方式清单 */
    SETTINGS,
}

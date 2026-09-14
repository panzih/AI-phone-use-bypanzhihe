package com.aiphone.assistant.ui

/**
 * 界面路由。
 *
 * 只有两个页面，暂时不引入 Navigation 库 —— 用最简单的状态切换就够了，
 * 少一个依赖少一层复杂度。等页面变多再换。
 */
enum class Screen {
    /** 主界面：中间是纸盒 logo，底下是输入框 */
    CONTROL,

    /** 设置页：模型 / 操作授权 / 开发者设置 */
    SETTINGS,
}

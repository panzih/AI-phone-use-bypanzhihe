package com.aiphone.assistant.ui

/**
 * 界面路由。
 *
 * 只有四个页面，暂时不引入 Navigation 库 —— 用最简单的状态切换就够了，
 * 少一个依赖少一层复杂度。等页面变多再换。
 * （副屏不再是独立页面，入口和状态都收进「设置 → 操作授权」）
 */
enum class Screen {
    /** 主界面：中间是纸盒 logo，底下是输入框 */
    CONTROL,

    /** 设置页：关于 / 模型 / 操作授权 / 开发者设置 */
    SETTINGS,

    /** 操作记录：手动做一遍，交给 AI 学成技能 */
    RECORDING,

    /** 定时任务：到点自动跑一条任务 */
    SCHEDULES,
}

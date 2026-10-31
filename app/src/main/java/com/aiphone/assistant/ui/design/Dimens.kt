package com.aiphone.assistant.ui.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 间距阶梯 —— 全应用只用这几档。
 *
 * 基准 4dp，所有值都是 4 的倍数。间距本身表达关系：
 * 同一组内用 [Space.sm]，换组用 [Space.xl]，换话题用 [Space.xxl]。
 */
object Space {
    /** 2dp —— 只用于文字内部的行距微调 */
    val xxs = 2.dp

    /** 4dp —— 图标与文字之间、紧邻元素 */
    val xs = 4.dp

    /** 8dp —— 同一行里的两段 */
    val sm = 8.dp

    /** 12dp —— 一行的内边距 */
    val md = 12.dp

    /** 16dp —— 页面左右边距（唯一允许贴边的地方） */
    val lg = 16.dp

    /** 20dp —— 卡片内边距 */
    val xl = 20.dp

    /** 24dp —— 分组之间 */
    val xxl = 24.dp

    /** 32dp —— 大段落之间、列表底部留白 */
    val huge = 32.dp

    /** 48dp —— 空状态、品牌区的大留白 */
    val giant = 48.dp
}

/**
 * 圆角家族：10 / 14 / 20 / 28。
 *
 * 卡片用 [Radius.lg]，按钮和输入框用 [Radius.md]，
 * 只有"气泡"和"胶囊"才用 [Radius.pill] —— 圆角也是语义。
 */
object Radius {
    /** 6dp —— 聊天气泡的"尖角" */
    val xs = 6.dp

    /** 10dp —— 小标签、段内选中块 */
    val sm = 10.dp

    /** 14dp —— 按钮、输入框 */
    val md = 14.dp

    /** 20dp —— 卡片 */
    val lg = 20.dp

    /** 28dp —— 对话框、底部输入容器 */
    val xl = 28.dp

    /** 胶囊 */
    val pill = 100.dp
}

/** Material 形状表：让 M3 自带组件（对话框、菜单、Snackbar）跟着同一套圆角走 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.sm),
    small = RoundedCornerShape(Radius.sm),
    medium = RoundedCornerShape(Radius.md),
    large = RoundedCornerShape(Radius.lg),
    extraLarge = RoundedCornerShape(Radius.xl),
)

/**
 * 交互尺寸。
 *
 * [touchTarget] 是所有可点元素的最小热区（Material 48dp）。
 * 视觉上图标可以只有 20dp，但**热区不能小** —— 手指按不准是最常见的"点不动"。
 */
object Size {
    val touchTarget = 48.dp
    val rowMinHeight = 60.dp
    val buttonHeight = 52.dp
    val iconSm = 16.dp
    val iconMd = 20.dp
    val iconLg = 24.dp
    val iconXl = 28.dp
    val badge = 40.dp
    val avatar = 36.dp
}

/**
 * 动效时长。
 *
 * 入场比退场慢一点（0.28s / 0.16s），"东西出现要看得清、消失要干脆"；
 * 超过 300ms 用户会开始觉得卡。
 */
object Motion {
    const val enter = 280
    const val exit = 160
    const val stateChange = 160
}

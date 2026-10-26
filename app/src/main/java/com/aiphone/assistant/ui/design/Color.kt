package com.aiphone.assistant.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 全应用唯一的颜色来源。
 *
 * ## 为什么不再用 dynamicColor
 *
 * 之前主题直接用系统的动态取色 —— 界面主色跟着壁纸变。看起来"很原生"，
 * 代价是**同一个软件在两台手机上不是同一个软件**：按钮一会儿紫一会儿绿、
 * 语义色（成功/警告/危险）和壁纸撞色之后彻底读不出来。
 *
 * 这里是「纸盒」自己的配色，跟壁纸无关：
 *
 *   画布  暖白（像纸），卡片比画布再亮一档
 *   文字  墨色三级（正文 / 次要 / 提示），靠亮度拉开层次
 *   主色  松墨绿 —— 一个"可以开始跑"的信号色，同时避开满大街的科技蓝
 *
 * 亮暗两套不是简单反相：暗色用**深灰而非纯黑**（纯黑配白字在 OLED 上有光晕，
 * 且卡片和画布会糊在一起），前景色降饱和、提亮度，保证对比度不掉。
 */
internal object Paper {
    val canvas = Color(0xFFF5F3EE)
    val surface = Color(0xFFFCFBF8)
    val sunken = Color(0xFFEFECE4)
    val outline = Color(0xFFE1DDD3)
    val outlineStrong = Color(0xFFCFC9BC)
    val ink = Color(0xFF1C1B17)
    val inkSecondary = Color(0xFF5E5A50)
    val inkTertiary = Color(0xFF8E887B)
    val accent = Color(0xFF1E6E5A)
    val accentSoft = Color(0xFFDBEAE2)
    val accentDeep = Color(0xFF123E32)
    val danger = Color(0xFFB3372B)
    val dangerSoft = Color(0xFFF7E3E0)
    val warning = Color(0xFF9A6210)
    val warningSoft = Color(0xFFF8EBD3)
    val success = Color(0xFF2C7A4B)
    val successSoft = Color(0xFFE2EFE5)
    val info = Color(0xFF2A6398)
    val infoSoft = Color(0xFFE3ECF5)
}

internal object Ink {
    val canvas = Color(0xFF13120E)
    val surface = Color(0xFF1D1C17)
    /**
     * 深色下"凹陷"要**比画布更暗**。
     *
     * 这一点和亮色相反，容易写错：亮色里画布最暗、卡片浮起来（更亮）；
     * 深色里卡片靠"更亮"浮起来，凹陷块就必须反过来往暗里走。
     * 之前把它写得比 surface 还亮，结果分段控件的选中项看起来像"被按下去"，
     * 而不是"被选中"。
     */
    val sunken = Color(0xFF0D0C08)
    val outline = Color(0xFF2E2B22)
    val outlineStrong = Color(0xFF453F33)
    val ink = Color(0xFFF2EFE7)
    val inkSecondary = Color(0xFFA9A395)
    val inkTertiary = Color(0xFF7C7768)
    val accent = Color(0xFF5BBFA2)
    val accentSoft = Color(0xFF1B3A31)
    val accentDeep = Color(0xFFA9DFCE)
    val danger = Color(0xFFE8837A)
    val dangerSoft = Color(0xFF3A201B)
    val warning = Color(0xFFD9A94F)
    val warningSoft = Color(0xFF332810)
    val success = Color(0xFF6FBF8B)
    val successSoft = Color(0xFF1C3323)
    val info = Color(0xFF7FB0DA)
    val infoSoft = Color(0xFF1B2A38)
}

/**
 * Material 配色表覆盖不到的那几个语义色。
 *
 * 成功 / 警告 / 信息在 M3 里没有对应角色，之前每个页面各自挑一个
 * `colorScheme.xxx` 凑，导致同一个"成功"在不同页面是三种绿。
 * 统一收到这里，通过 [LocalAppAccents] 下发。
 */
@Immutable
data class AppAccents(
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val info: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
    val canvas: Color,
    val sunken: Color,
    val hairline: Color,
)

internal val LightAccents = AppAccents(
    success = Paper.success,
    successContainer = Paper.successSoft,
    onSuccessContainer = Color(0xFF17381F),
    warning = Paper.warning,
    warningContainer = Paper.warningSoft,
    onWarningContainer = Color(0xFF3D2708),
    info = Paper.info,
    infoContainer = Paper.infoSoft,
    onInfoContainer = Color(0xFF11293D),
    canvas = Paper.canvas,
    sunken = Paper.sunken,
    hairline = Paper.outline,
)

internal val DarkAccents = AppAccents(
    success = Ink.success,
    successContainer = Ink.successSoft,
    onSuccessContainer = Color(0xFFC4E9CF),
    warning = Ink.warning,
    warningContainer = Ink.warningSoft,
    onWarningContainer = Color(0xFFF2DCAF),
    info = Ink.info,
    infoContainer = Ink.infoSoft,
    onInfoContainer = Color(0xFFC7DDF0),
    canvas = Ink.canvas,
    sunken = Ink.sunken,
    hairline = Ink.outline,
)

/** 语义扩展色的下发通道（由 [com.aiphone.assistant.ui.theme.AiPhoneTheme] 注入） */
val LocalAppAccents = staticCompositionLocalOf { LightAccents }

/**
 * 读配色的快捷入口：
 *
 * ```kotlin
 * AppTheme.accents.warning    // 警告色
 * AppTheme.accents.sunken     // 凹陷块（统计卡片那种底）
 * ```
 *
 * 只放 M3 配色表覆盖不到的语义色；基础的 primary/surface/onSurface 一律
 * 走 `MaterialTheme.colorScheme`，免得同一件事有两个读法。
 */
object AppTheme {
    val accents: AppAccents
        @Composable @ReadOnlyComposable get() = LocalAppAccents.current
}

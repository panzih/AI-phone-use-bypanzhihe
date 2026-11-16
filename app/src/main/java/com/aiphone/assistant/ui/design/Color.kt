package com.aiphone.assistant.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 全应用唯一的颜色来源。
 *
 * ## 为什么不用 dynamicColor
 *
 * 界面主色跟着壁纸变，代价是**同一个软件在两台手机上不是同一个软件**：
 * 按钮一会儿紫一会儿绿、语义色（成功/警告/危险）和壁纸撞色之后彻底读不出来。
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
    val canvas = Color(0xFFF7F5F0)
    val surface = Color(0xFFFFFFFF)
    val sunken = Color(0xFFEFEBE2)
    val outline = Color(0xFFE7E2D6)
    val outlineStrong = Color(0xFFD2CBBA)
    val ink = Color(0xFF1A1814)
    val inkSecondary = Color(0xFF575349)
    val inkTertiary = Color(0xFF8A8475)
    val accent = Color(0xFF0E5C46)
    val accentSoft = Color(0xFFD6EAE0)
    val accentDeep = Color(0xFF073328)
    val danger = Color(0xFFAC3529)
    val dangerSoft = Color(0xFFF7E3DF)
    val warning = Color(0xFF8F5B0D)
    val warningSoft = Color(0xFFF8EACF)
    val success = Color(0xFF276F45)
    val successSoft = Color(0xFFE0EEE5)
    val info = Color(0xFF285C8E)
    val infoSoft = Color(0xFFE1EBF5)
}

internal object Ink {
    val canvas = Color(0xFF0F0E0B)
    val surface = Color(0xFF191813)
    /**
     * 深色下"凹陷"要**比画布更暗**。
     *
     * 这一点和亮色相反：亮色里画布最暗、卡片浮起来（更亮）；
     * 深色里卡片靠"更亮"浮起来，凹陷块就必须反过来往暗里走。
     */
    val sunken = Color(0xFF090806)
    val outline = Color(0xFF2A2720)
    val outlineStrong = Color(0xFF453F31)
    val ink = Color(0xFFF2EFE7)
    val inkSecondary = Color(0xFFA9A395)
    val inkTertiary = Color(0xFF7D7869)
    val accent = Color(0xFF6BD3B0)
    val accentSoft = Color(0xFF15362C)
    val accentDeep = Color(0xFFB4E4D5)
    val danger = Color(0xFFE88A81)
    val dangerSoft = Color(0xFF3A1F1A)
    val warning = Color(0xFFD9AC55)
    val warningSoft = Color(0xFF342811)
    val success = Color(0xFF78C492)
    val successSoft = Color(0xFF1C3323)
    val info = Color(0xFF82B2DB)
    val infoSoft = Color(0xFF1A2937)
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
    onSuccessContainer = Color(0xFF16361E),
    warning = Paper.warning,
    warningContainer = Paper.warningSoft,
    onWarningContainer = Color(0xFF3B2608),
    info = Paper.info,
    infoContainer = Paper.infoSoft,
    onInfoContainer = Color(0xFF10283B),
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
    onWarningContainer = Color(0xFFF0D9AC),
    info = Ink.info,
    infoContainer = Ink.infoSoft,
    onInfoContainer = Color(0xFFC9DFF2),
    canvas = Ink.canvas,
    sunken = Ink.sunken,
    hairline = Ink.outline,
)

internal val LocalAppAccents = staticCompositionLocalOf { LightAccents }

/** 语义扩展色的唯一读取入口：`AppTheme.accents.success` */
object AppTheme {
    val accents: AppAccents
        @Composable
        @ReadOnlyComposable
        get() = LocalAppAccents.current
}

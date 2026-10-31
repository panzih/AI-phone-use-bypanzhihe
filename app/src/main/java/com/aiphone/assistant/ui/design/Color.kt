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
    val canvas = Color(0xFFF3F1EB)
    val surface = Color(0xFFFEFDFA)
    val sunken = Color(0xFFEAE7DE)
    val outline = Color(0xFFE2DCD0)
    val outlineStrong = Color(0xFFCBC4B4)
    val ink = Color(0xFF191813)
    val inkSecondary = Color(0xFF5A564C)
    val inkTertiary = Color(0xFF8B8577)
    val accent = Color(0xFF186A56)
    val accentSoft = Color(0xFFD8EAE1)
    val accentDeep = Color(0xFF0D3B2F)
    val danger = Color(0xFFB23A2E)
    val dangerSoft = Color(0xFFF6E1DD)
    val warning = Color(0xFF96600F)
    val warningSoft = Color(0xFFF7E9CE)
    val success = Color(0xFF2A7648)
    val successSoft = Color(0xFFDFEDE3)
    val info = Color(0xFF2A6094)
    val infoSoft = Color(0xFFE0EAF4)
}

internal object Ink {
    val canvas = Color(0xFF12110D)
    val surface = Color(0xFF1C1B16)
    /**
     * 深色下"凹陷"要**比画布更暗**。
     *
     * 这一点和亮色相反：亮色里画布最暗、卡片浮起来（更亮）；
     * 深色里卡片靠"更亮"浮起来，凹陷块就必须反过来往暗里走。
     */
    val sunken = Color(0xFF0C0B08)
    val outline = Color(0xFF2D2A21)
    val outlineStrong = Color(0xFF474134)
    val ink = Color(0xFFF1EEE6)
    val inkSecondary = Color(0xFFA8A294)
    val inkTertiary = Color(0xFF7B7667)
    val accent = Color(0xFF63C7A7)
    val accentSoft = Color(0xFF193A30)
    val accentDeep = Color(0xFFA9DFCE)
    val danger = Color(0xFFE9867D)
    val dangerSoft = Color(0xFF3B201B)
    val warning = Color(0xFFDBAE57)
    val warningSoft = Color(0xFF342811)
    val success = Color(0xFF74C18E)
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

package com.aiphone.assistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import com.aiphone.assistant.ui.design.AppShapes
import com.aiphone.assistant.ui.design.AppTypography
import com.aiphone.assistant.ui.design.DarkAccents
import com.aiphone.assistant.ui.design.Ink
import com.aiphone.assistant.ui.design.LightAccents
import com.aiphone.assistant.ui.design.LocalAppAccents
import com.aiphone.assistant.ui.design.Paper

/**
 * 主题入口。
 *
 * 和 0.10.x 相比有两处变化，都是"统一视觉"的前提：
 *
 * 1. **不再跟随系统动态取色。** 界面主色如果跟着壁纸走，同一个软件在两台
 *    手机上就不是同一个软件，语义色还可能与壁纸撞色。品牌色固定成松墨绿。
 * 2. **容器色显式指定。** Material 自带的 surfaceContainer 是带紫调的灰，
 *    盖在暖纸画布上会发脏；这里把整条容器阶梯都对齐到同一套暖灰。
 *
 * 深浅色不是简单反相：画布用深灰不用纯黑（纯黑配白字在 OLED 上有光晕，
 * 卡片也会和画布糊在一起），前景色降饱和、提亮度，对比度按 WCAG AA 校过。
 */
private val LightScheme: ColorScheme = lightColorScheme(
    primary = Paper.accent,
    onPrimary = Color.White,
    primaryContainer = Paper.accentSoft,
    onPrimaryContainer = Paper.accentDeep,
    inversePrimary = Paper.accent,

    secondary = Paper.inkSecondary,
    onSecondary = Color.White,
    secondaryContainer = Paper.sunken,
    onSecondaryContainer = Paper.ink,

    tertiary = Paper.info,
    onTertiary = Color.White,
    tertiaryContainer = Paper.infoSoft,
    onTertiaryContainer = Paper.ink,

    background = Paper.canvas,
    onBackground = Paper.ink,
    surface = Paper.surface,
    onSurface = Paper.ink,
    surfaceVariant = Paper.sunken,
    onSurfaceVariant = Paper.inkSecondary,
    surfaceTint = Paper.accent,

    inverseSurface = Paper.ink,
    inverseOnSurface = Paper.surface,

    error = Paper.danger,
    onError = Color.White,
    errorContainer = Paper.dangerSoft,
    onErrorContainer = Paper.danger,

    outline = Paper.outlineStrong,
    outlineVariant = Paper.outline,
    scrim = Color(0x99000000),
).copy(
    surfaceBright = Paper.surface,
    surfaceDim = Paper.sunken,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Paper.surface,
    surfaceContainer = Paper.surface,
    surfaceContainerHigh = Paper.surface,
    surfaceContainerHighest = Paper.sunken,
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Ink.accent,
    onPrimary = Color(0xFF06251C),
    primaryContainer = Ink.accentSoft,
    onPrimaryContainer = Ink.accentDeep,
    inversePrimary = Ink.accent,

    secondary = Ink.inkSecondary,
    onSecondary = Color(0xFF14130F),
    secondaryContainer = Ink.sunken,
    onSecondaryContainer = Ink.ink,

    tertiary = Ink.info,
    onTertiary = Color(0xFF07151F),
    tertiaryContainer = Ink.infoSoft,
    onTertiaryContainer = Ink.ink,

    background = Ink.canvas,
    onBackground = Ink.ink,
    surface = Ink.surface,
    onSurface = Ink.ink,
    surfaceVariant = Ink.sunken,
    onSurfaceVariant = Ink.inkSecondary,
    surfaceTint = Ink.accent,

    inverseSurface = Ink.ink,
    inverseOnSurface = Ink.canvas,

    error = Ink.danger,
    onError = Color(0xFF2A0F0B),
    errorContainer = Ink.dangerSoft,
    onErrorContainer = Ink.danger,

    outline = Ink.outlineStrong,
    outlineVariant = Ink.outline,
    scrim = Color(0xCC000000),
).copy(
    surfaceBright = Ink.surface,
    surfaceDim = Ink.canvas,
    surfaceContainerLowest = Ink.canvas,
    surfaceContainerLow = Ink.surface,
    surfaceContainer = Ink.surface,
    surfaceContainerHigh = Ink.surface,
    surfaceContainerHighest = Ink.sunken,
)

/** 语义扩展色（成功/警告/信息）。M3 配色表里没有这三个角色，见 [LocalAppAccents]。 */
@Composable
fun AiPhoneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    val accents = if (darkTheme) DarkAccents else LightAccents

    CompositionLocalProvider(LocalAppAccents provides accents) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

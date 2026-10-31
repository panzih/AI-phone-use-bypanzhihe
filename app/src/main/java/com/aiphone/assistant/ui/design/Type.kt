package com.aiphone.assistant.ui.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字号阶梯 —— 一套能撑住"标题 / 正文 / 脚注"三层的尺寸。
 *
 * 三条规则：
 *
 * 1. **字重只有 Regular(400) 和 Medium(500)。** 中文在低端 Android 上
 *    没有真正的 600/700 字重，系统会合成加粗 —— 笔画糊成一坨。Medium
 *    已经够区分层级，再重反而更难读。
 * 2. **正文 16sp 起步。** 中文 15sp 在 6.7 寸屏上偏小，长段落读起来费劲；
 *    16sp 配 1.5 倍行高是"能连续读十分钟"的下限。
 * 3. **letterSpacing 一律 0。** Material 默认给拉丁字母留了字距，
 *    套到中文上会让"标题"两个字之间凭空多一道缝。
 *
 * 行高单位是 sp，不写 dp —— 用户放大系统字号时整体一起缩放，不会破版。
 */
private fun cjk(size: Float, lineHeight: Float, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = 0.sp,
)

val AppTypography = Typography(
    // ---- 品牌区、空状态的大标题 ----
    displayLarge = cjk(34f, 44f, FontWeight.Medium),
    displayMedium = cjk(30f, 40f, FontWeight.Medium),
    displaySmall = cjk(26f, 36f, FontWeight.Medium),

    // ---- 页面标题 ----
    headlineLarge = cjk(28f, 38f, FontWeight.Medium),
    headlineMedium = cjk(24f, 34f, FontWeight.Medium),
    headlineSmall = cjk(20f, 30f, FontWeight.Medium),

    // ---- 区块 / 行标题 ----
    titleLarge = cjk(19f, 28f, FontWeight.Medium),
    titleMedium = cjk(17f, 26f, FontWeight.Medium),
    titleSmall = cjk(15f, 23f, FontWeight.Medium),

    // ---- 正文 ----
    bodyLarge = cjk(16f, 25f),
    bodyMedium = cjk(14.5f, 22f),
    bodySmall = cjk(13f, 20f),

    // ---- 标签 / 按钮 / 角标 ----
    labelLarge = cjk(15f, 21f, FontWeight.Medium),
    labelMedium = cjk(13f, 18f, FontWeight.Medium),
    labelSmall = cjk(11.5f, 17f, FontWeight.Medium),
)

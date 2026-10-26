package com.aiphone.assistant.ui.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字号阶梯 —— 7 个实际会用的尺寸，外加更衣室里的几个大号。
 *
 * 三条规则：
 *
 * 1. **字重只有 Regular(400) 和 Medium(500)。** 中文在低端 Android 上
 *    没有真正的 600/700 字重，系统会合成加粗 —— 笔画糊成一坨。用 Medium
 *    已经够区分层级，再重反而更难读。
 * 2. **字越小，行高倍数越大。** 15sp 正文行高 1.53，12.5sp 说明文字行高 1.52；
 *    小字行高不足会挤成一片灰。
 * 3. **letterSpacing 一律 0。** Material 默认给拉丁字母留了字距，
 *    套到中文上会让"标题"两个字之间凭空多一道缝。
 *
 * 行高单位是 sp，不写 dp —— 用户放大系统字号时整体一起缩放，不会破版。
 */
private fun cjk(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = 0.sp,
)

val AppTypography = Typography(
    // ---- 大标题：只有"关于"页的品牌区和空状态用得到 ----
    displayLarge = cjk(32, 40, FontWeight.Medium),
    displayMedium = cjk(28, 36, FontWeight.Medium),
    displaySmall = cjk(24, 32, FontWeight.Medium),

    // ---- 页面内标题 ----
    headlineLarge = cjk(24, 32, FontWeight.Medium),
    headlineMedium = cjk(20, 28, FontWeight.Medium),
    headlineSmall = cjk(18, 26, FontWeight.Medium),

    // ---- 行标题 ----
    titleLarge = cjk(18, 26, FontWeight.Medium),
    titleMedium = cjk(16, 24, FontWeight.Medium),
    titleSmall = cjk(14, 20, FontWeight.Medium),

    // ---- 正文 ----
    bodyLarge = cjk(15, 23),
    bodyMedium = cjk(14, 21),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.sp,
    ),

    // ---- 标签 / 按钮 / 角标 ----
    labelLarge = cjk(14, 20, FontWeight.Medium),
    labelMedium = cjk(12, 16, FontWeight.Medium),
    labelSmall = cjk(11, 16, FontWeight.Medium),
)

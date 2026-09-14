package com.aiphone.assistant.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * 配色遵循 Google 原生 Material 3：
 * Android 12+ 直接用系统的动态取色（dynamicColor），
 * 这样界面颜色会跟着用户的壁纸走 —— 这就是"谷歌原生"的观感来源。
 * Android 12 以下退回到下面这套手动定义的基线配色。
 */
private val LightColors = lightColorScheme()
private val DarkColors = darkColorScheme()

@Composable
fun AiPhoneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // 动态取色：Android 12 (API 31) 及以上默认开启
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content,
    )
}

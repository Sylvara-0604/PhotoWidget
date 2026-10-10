package com.android.gallery3d.photowidget.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * 应用主题：Material 3（Expressive 设计语言）。
 *
 * 配色照 Google M3 配色文档：优先**动态取色**（Material You，从壁纸取调色板；minSdk 33 恒可用），
 * 动态取色不可用时回退到 M3 基线角色集（见 Color.kt）。
 *
 * **一处实测限制**：Compose material3 **1.4.0** 里 `MaterialExpressiveTheme` 与 `MotionScheme`
 * 被标成 `internal`（javap 能看到 `MaterialTheme(colorScheme, motionScheme, …)` 重载，但 Kotlin 侧
 * 引用会报 "it is internal in file"），所以 Compose 侧只能用公开的 `MaterialTheme`；
 * Expressive 的**形状/动效/排版令牌**由 XML 主题（`Theme.Material3Expressive.DayNight.NoActionBar`，
 * 来自 material-1.14.0，共 45 个可用主题）与小部件样式承担。
 */
@Composable
fun PhotoWidgetTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> M3DarkColorScheme
        else -> M3LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

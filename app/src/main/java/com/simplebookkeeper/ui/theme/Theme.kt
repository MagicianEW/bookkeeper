package com.simplebookkeeper.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 语义色：收入/支出（两种模式通用）
val IncomeGreen = Color(0xFF4CAF50)
val ExpenseRed = Color(0xFFF44336)

// ─── 浅色模式：白底 + 浅蓝主题（参考统计页年汇总 #BBDEFB 色调）───
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1976D2),           // 深蓝——TopBar/FAB/主操作
    onPrimary = Color.White,               // 主色上的文字
    primaryContainer = Color(0xFFBBDEFB),  // 浅蓝——年汇总卡片底色
    onPrimaryContainer = Color(0xFF0D47A1),// 浅蓝底上的文字
    secondary = Color(0xFF42A5F5),         // 辅助蓝
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3F2FD),
    onSecondaryContainer = Color(0xFF0D47A1),
    tertiary = Color(0xFF29B6F6),
    background = Color(0xFFFAFAFA),        // 近白
    onBackground = Color(0xFF1C1B1F),
    surface = Color.White,                 // 卡片/表面
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFF0F4F8),
    onSurfaceVariant = Color(0xFF44474E),
    outline = Color(0xFFC4C7CF),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

// ─── 深色模式：黑底 + 深蓝主题（参考账本页汇总卡片 #1976D2 色调）───
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF1976D2),           // 深蓝——与浅色模式同一主色，保持品牌一致
    onPrimary = Color.White,
    primaryContainer = Color(0xFF0D47A1),  // 更深的蓝——深色模式下的容器色
    onPrimaryContainer = Color(0xFFBBDEFB),
    secondary = Color(0xFF42A5F5),
    onSecondary = Color(0xFF003258),
    secondaryContainer = Color(0xFF004881),
    onSecondaryContainer = Color(0xFFBBDEFB),
    tertiary = Color(0xFF29B6F6),
    background = Color(0xFF121212),        // 纯黑
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1E1E2E),           // 深灰偏蓝——与蓝色主题呼应
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF2A2A3A),    // 深灰偏蓝变体
    onSurfaceVariant = Color(0xFFC4C0CA),
    outline = Color(0xFF44474E),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
)

@Composable
fun SimpleBookkeeperTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

package com.jjw.easygallery.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.jjw.easygallery.core.domain.model.CATEGORY_COLOR_COUNT

/**
 * 카테고리 8색. 사용자가 고른 색이 테마마다 바뀌면 안 되므로 MaterialTheme 동적 색과 독립이고,
 * 다크/라이트에서 배경 대비만 맞춘 두 세트를 둔다. 순서: 빨강·주황·노랑·초록·청록·파랑·보라·분홍.
 */
private val LightCategoryColors = listOf(
    Color(0xFFD32F2F),
    Color(0xFFEF6C00),
    Color(0xFFF9A825),
    Color(0xFF2E7D32),
    Color(0xFF00838F),
    Color(0xFF1565C0),
    Color(0xFF6A1B9A),
    Color(0xFFC2185B),
)

private val DarkCategoryColors = listOf(
    Color(0xFFEF5350),
    Color(0xFFFF9800),
    Color(0xFFFFD54F),
    Color(0xFF66BB6A),
    Color(0xFF4DD0E1),
    Color(0xFF64B5F6),
    Color(0xFFBA68C8),
    Color(0xFFF06292),
)

@Composable
fun categoryColor(index: Int): Color {
    val palette = if (isSystemInDarkTheme()) DarkCategoryColors else LightCategoryColors
    return palette[index.mod(CATEGORY_COLOR_COUNT)]
}

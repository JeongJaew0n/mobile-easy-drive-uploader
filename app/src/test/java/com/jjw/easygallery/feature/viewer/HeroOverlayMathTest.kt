package com.jjw.easygallery.feature.viewer

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class HeroOverlayMathTest {

    private val container = IntSize(1000, 2000)

    @Test
    fun `landscape image is letterboxed vertically and centered`() {
        // 4:3 → 너비 1000 에 맞추면 높이 750, 세로 중앙 (2000-750)/2 = 625
        assertEquals(Rect(0f, 625f, 1000f, 1375f), fittedRect(container, 4000, 3000))
    }

    @Test
    fun `portrait image is pillarboxed horizontally`() {
        // 9:16 → 높이 2000 에 맞추면 너비 1125 > 1000 이므로 너비 기준: 1000×1777.78
        val rect = fittedRect(container, 900, 1600)
        assertEquals(0f, rect.left, 0.01f)
        assertEquals(1000f, rect.width, 0.01f)
        assertEquals(1777.78f, rect.height, 0.01f)
        assertEquals((2000f - 1777.78f) / 2f, rect.top, 0.01f)
    }

    @Test
    fun `unknown size fills the container`() {
        assertEquals(Rect(0f, 0f, 1000f, 2000f), fittedRect(container, 0, 0))
    }
}

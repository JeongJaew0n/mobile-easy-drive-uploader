package com.jjw.easygallery.feature.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomMathTest {

    private val container = IntSize(1000, 2000)

    @Test
    fun `clamp keeps zoomed content covering the container`() {
        // 2배: 가로 여유 500, 세로 여유 1000
        assertEquals(Offset(500f, 1000f), clampOffset(Offset(900f, 5000f), 2f, container))
        assertEquals(Offset(-500f, -1000f), clampOffset(Offset(-900f, -5000f), 2f, container))
        assertEquals(Offset(120f, -300f), clampOffset(Offset(120f, -300f), 2f, container))
    }

    @Test
    fun `clamp at 1x forces the origin`() {
        assertEquals(Offset.Zero, clampOffset(Offset(40f, -70f), 1f, container))
    }

    @Test
    fun `focal zoom keeps the point under the finger in place`() {
        // 중앙에서 확대하면 offset 은 그대로
        assertEquals(Offset.Zero, focalZoomOffset(Offset.Zero, Offset.Zero, 2f))
        // 중심에서 오른쪽 100px 지점을 2배 확대하면 콘텐츠가 왼쪽으로 100px 밀려야 그 지점이 고정된다
        assertEquals(Offset(-100f, 0f), focalZoomOffset(Offset.Zero, Offset(100f, 0f), 2f))
        // 축소(0.5배)는 반대로 당겨온다
        assertEquals(Offset(50f, 0f), focalZoomOffset(Offset.Zero, Offset(100f, 0f), 0.5f))
    }

    @Test
    fun `focal zoom composes with an existing offset`() {
        val once = focalZoomOffset(Offset.Zero, Offset(100f, 0f), 2f)
        val twice = focalZoomOffset(once, Offset(100f, 0f), 2f)
        assertEquals(Offset(-300f, 0f), twice)
    }
}

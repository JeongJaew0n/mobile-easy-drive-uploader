package com.jjw.easygallery.feature.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 칸 크기 단계와 날짜 손잡이 계산(`docs/plans/ux-round2/spec.md` §6) */
class GalleryGridZoomTest {

    @Test
    fun `벌리면 한 단계 커지고 오므리면 한 단계 작아진다`() {
        assertEquals(3, nextCellStep(2, zoom = 1.6f))
        assertEquals(3, nextCellStep(2, zoom = 3.0f)) // 크게 벌려도 한 칸
        assertEquals(1, nextCellStep(2, zoom = 0.6f))
    }

    @Test
    fun `문턱 안의 작은 움직임은 그대로`() {
        assertEquals(2, nextCellStep(2, zoom = 1.1f))
        assertEquals(2, nextCellStep(2, zoom = 0.9f))
    }

    @Test
    fun `양 끝을 넘지 않는다`() {
        assertEquals(0, nextCellStep(0, zoom = 0.5f))
        assertEquals(CELL_SIZE_STEPS_DP.lastIndex, nextCellStep(CELL_SIZE_STEPS_DP.lastIndex, zoom = 2f))
    }

    @Test
    fun `손잡이 비율은 처음과 끝 항목에 닿는다`() {
        assertEquals(0, indexForFraction(0f, 100))
        assertEquals(99, indexForFraction(1f, 100))
        assertEquals(50, indexForFraction(0.505f, 100))
        assertEquals(0, indexForFraction(0.5f, 0))
    }

    @Test
    fun `말풍선은 그 자리 앞의 마지막 머리글`() {
        val headers = listOf(0, 10, 25)
        assertEquals(0, headerIndexFor(5, headers))
        assertEquals(10, headerIndexFor(10, headers))
        assertEquals(25, headerIndexFor(99, headers))
        assertNull(headerIndexFor(3, listOf(5)))
    }
}

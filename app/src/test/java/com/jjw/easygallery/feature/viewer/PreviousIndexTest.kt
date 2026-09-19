package com.jjw.easygallery.feature.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

/** 상세보기에서 사진을 지웠을 때 갈 자리 */
class PreviousIndexTest {

    @Test
    fun `가운데를 지우면 바로 이전 자리`() {
        // [0,1,2,3,4] 에서 3번을 지우면 [0,1,2,3] 이 되고 2번(원래 2번)이 보인다
        assertEquals(2, previousIndexAfterRemoval(removedIndex = 3, newSize = 4))
    }

    @Test
    fun `첫 번째를 지우면 이전이 없어 그 자리에 머문다`() {
        assertEquals(0, previousIndexAfterRemoval(removedIndex = 0, newSize = 4))
    }

    @Test
    fun `마지막을 지워도 이전 자리는 목록 안이다`() {
        assertEquals(3, previousIndexAfterRemoval(removedIndex = 4, newSize = 4))
    }

    @Test
    fun `한 장만 남았다가 지우면 0`() {
        assertEquals(0, previousIndexAfterRemoval(removedIndex = 0, newSize = 0))
    }

    @Test
    fun `목록이 줄어든 만큼 넘어가지 않는다`() {
        // 여러 장이 한꺼번에 사라져도 범위를 벗어나지 않는다
        assertEquals(0, previousIndexAfterRemoval(removedIndex = 9, newSize = 1))
    }
}

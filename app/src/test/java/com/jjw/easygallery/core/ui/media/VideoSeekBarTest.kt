package com.jjw.easygallery.core.ui.media

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoSeekBarTest {

    @Test
    fun `누른 가로 위치를 0과 1 사이 분수로`() {
        assertEquals(0.25f, fractionAt(250f, 1000), 0.0001f)
        assertEquals(0f, fractionAt(-30f, 1000), 0f)
        assertEquals(1f, fractionAt(1200f, 1000), 0f)
    }

    @Test
    fun `폭을 모르면 0`() {
        assertEquals(0f, fractionAt(100f, 0), 0f)
    }
}

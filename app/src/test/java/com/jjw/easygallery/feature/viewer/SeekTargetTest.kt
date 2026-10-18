package com.jjw.easygallery.feature.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class SeekTargetTest {

    @Test
    fun `두 번 톡은 10초씩, 처음과 끝을 넘지 않는다`() {
        assertEquals(25_000L, seekTarget(current = 15_000, duration = 60_000, forward = true))
        assertEquals(5_000L, seekTarget(current = 15_000, duration = 60_000, forward = false))
        assertEquals(0L, seekTarget(current = 4_000, duration = 60_000, forward = false))
        assertEquals(60_000L, seekTarget(current = 55_000, duration = 60_000, forward = true))
    }

    @Test
    fun `길이를 아직 모르면 앞쪽 끝만 자른다`() {
        assertEquals(10_000L, seekTarget(current = 0, duration = -9_223_372_036_854_775L, forward = true))
        assertEquals(0L, seekTarget(current = 3_000, duration = 0, forward = false))
    }
}

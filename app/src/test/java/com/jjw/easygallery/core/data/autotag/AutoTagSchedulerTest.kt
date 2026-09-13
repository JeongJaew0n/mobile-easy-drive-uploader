package com.jjw.easygallery.core.data.autotag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class AutoTagSchedulerTest {

    @Test
    fun `아직 오늘 그 시각이 오지 않았으면 오늘로 잡는다`() {
        val now = LocalDateTime.of(2026, 9, 13, 1, 30)
        val delay = AutoTagScheduler.delayUntil(FOUR_AM, now)
        assertEquals(150, delay.toMinutes()) // 01:30 → 04:00
    }

    @Test
    fun `이미 지났으면 내일 같은 시각으로 잡는다`() {
        val now = LocalDateTime.of(2026, 9, 13, 4, 30)
        val delay = AutoTagScheduler.delayUntil(FOUR_AM, now)
        assertEquals(23 * 60 + 30, delay.toMinutes()) // 04:30 → 다음날 04:00
    }

    @Test
    fun `정각에 걸리면 하루를 기다린다`() {
        val now = LocalDateTime.of(2026, 9, 13, 4, 0)
        assertEquals(24 * 60, AutoTagScheduler.delayUntil(FOUR_AM, now).toMinutes())
    }

    @Test
    fun `자정 직전에도 다음 시각을 정확히 찾는다`() {
        val now = LocalDateTime.of(2026, 9, 13, 23, 59)
        assertEquals(4 * 60 + 1, AutoTagScheduler.delayUntil(FOUR_AM, now).toMinutes())
    }

    @Test
    fun `시각 변환은 서로 역이다`() {
        val minuteOfDay = AutoTagScheduler.minuteOfDay(hour = 21, minute = 45)
        assertEquals(21, AutoTagScheduler.hourOf(minuteOfDay))
        assertEquals(45, AutoTagScheduler.minuteOf(minuteOfDay))
    }

    @Test
    fun `하루 범위를 벗어난 값은 거른다`() {
        assertTrue(AutoTagScheduler.isValid(0))
        assertTrue(AutoTagScheduler.isValid(24 * 60 - 1))
        assertFalse(AutoTagScheduler.isValid(-1))
        assertFalse(AutoTagScheduler.isValid(24 * 60))
    }

    private companion object {
        const val FOUR_AM = 4 * 60
    }
}

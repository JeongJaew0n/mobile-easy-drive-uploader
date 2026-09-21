package com.jjw.easygallery.feature.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class DateJumpTest {

    private val counts = mapOf(
        LocalDate.of(2021, 5, 10) to 3,
        LocalDate.of(2021, 5, 11) to 1,
        LocalDate.of(2023, 12, 1) to 2,
        LocalDate.of(2026, 2, 20) to 4,
    )

    private val start = YearMonth.of(2021, 5)
    private val end = YearMonth.of(2026, 2)

    /** 사진 없는 날도 고를 수 있으니, 사진 없는 해로도 갈 수 있어야 한다 */
    @Test
    fun `범위 안 모든 연도를 최신순으로 담는다`() {
        assertEquals(listOf(2026, 2025, 2024, 2023, 2022, 2021), DateJump.yearsInRange(start, end))
    }

    @Test
    fun `한 해짜리 범위`() {
        assertEquals(listOf(2026), DateJump.yearsInRange(YearMonth.of(2026, 1), YearMonth.of(2026, 9)))
    }

    @Test
    fun `달력이 다루지 않는 달만 막는다`() {
        assertTrue(DateJump.inRange(2023, 7, start, end)) // 사진은 없지만 범위 안 → 갈 수 있다
        assertTrue(DateJump.inRange(2021, 5, start, end)) // 경계 포함
        assertTrue(DateJump.inRange(2026, 2, start, end))
        assertFalse(DateJump.inRange(2021, 4, start, end)) // 범위 이전
        assertFalse(DateJump.inRange(2026, 3, start, end)) // 범위 이후
    }

    @Test
    fun `연도별 사진 있는 달만`() {
        assertEquals(setOf(5), DateJump.monthsWithPhotos(counts, 2021, start, end))
        assertEquals(setOf(12), DateJump.monthsWithPhotos(counts, 2023, start, end))
        assertEquals(emptySet<Int>(), DateJump.monthsWithPhotos(counts, 2022, start, end))
    }

    @Test
    fun `달력 범위 밖 사진은 세지 않는다`() {
        // 기기 시계가 틀린 채 찍혀 타임스탬프가 미래인 파일
        val withFuture = counts + (LocalDate.of(2030, 1, 1) to 1)
        assertEquals(emptySet<Int>(), DateJump.monthsWithPhotos(withFuture, 2030, start, end))
    }

    @Test
    fun `점프 대상은 달력 범위 안으로 당겨진다`() {
        assertEquals(YearMonth.of(2023, 12), DateJump.target(2023, 12, start, end))
        assertEquals(start, DateJump.target(2020, 1, start, end)) // 범위 이전
        assertEquals(end, DateJump.target(2026, 9, start, end)) // 범위 이후
    }
}

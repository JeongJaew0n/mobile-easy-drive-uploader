package com.jjw.easygallery.feature.gallery

import org.junit.Assert.assertEquals
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

    @Test
    fun `사진 있는 연도만 최신순으로`() {
        assertEquals(listOf(2026, 2023, 2021), DateJump.yearsWithPhotos(counts))
    }

    @Test
    fun `연도별 사진 있는 달만`() {
        assertEquals(setOf(5), DateJump.monthsWithPhotos(counts, 2021))
        assertEquals(setOf(12), DateJump.monthsWithPhotos(counts, 2023))
        assertEquals(emptySet<Int>(), DateJump.monthsWithPhotos(counts, 2022))
    }

    @Test
    fun `점프 대상은 달력 범위 안으로 당겨진다`() {
        val start = YearMonth.of(2021, 5)
        val end = YearMonth.of(2026, 2)
        assertEquals(YearMonth.of(2023, 12), DateJump.target(2023, 12, start, end))
        assertEquals(start, DateJump.target(2020, 1, start, end)) // 범위 이전
        assertEquals(end, DateJump.target(2026, 9, start, end)) // 범위 이후
    }
}

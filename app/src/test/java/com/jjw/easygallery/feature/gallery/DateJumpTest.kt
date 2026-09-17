package com.jjw.easygallery.feature.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `사진 있는 연도만 최신순으로`() {
        assertEquals(listOf(2026, 2023, 2021), DateJump.yearsWithPhotos(counts, start, end))
    }

    @Test
    fun `연도별 사진 있는 달만`() {
        assertEquals(setOf(5), DateJump.monthsWithPhotos(counts, 2021, start, end))
        assertEquals(setOf(12), DateJump.monthsWithPhotos(counts, 2023, start, end))
        assertEquals(emptySet<Int>(), DateJump.monthsWithPhotos(counts, 2022, start, end))
    }

    @Test
    fun `달력 범위 밖 사진은 연도 칩에 넣지 않는다`() {
        // 기기 시계가 틀린 채 찍혀 타임스탬프가 미래인 파일
        val withFuture = counts + (LocalDate.of(2030, 1, 1) to 1)
        assertEquals(listOf(2026, 2023, 2021), DateJump.yearsWithPhotos(withFuture, start, end))
        assertEquals(emptySet<Int>(), DateJump.monthsWithPhotos(withFuture, 2030, start, end))
    }

    @Test
    fun `사진 없는 해에서 열면 가장 가까운 해로 당긴다`() {
        val years = DateJump.yearsWithPhotos(counts, start, end) // 2026, 2023, 2021
        assertEquals(2023, DateJump.nearestYear(years, 2022)) // 2021 보다 2023 이 가깝다
        assertEquals(2021, DateJump.nearestYear(years, 2020))
        assertEquals(2026, DateJump.nearestYear(years, 2030))
        assertEquals(2023, DateJump.nearestYear(years, 2023)) // 있는 해는 그대로
    }

    @Test
    fun `사진이 하나도 없으면 당길 해가 없다`() {
        assertNull(DateJump.nearestYear(emptyList(), 2024))
    }

    @Test
    fun `점프 대상은 달력 범위 안으로 당겨진다`() {
        assertEquals(YearMonth.of(2023, 12), DateJump.target(2023, 12, start, end))
        assertEquals(start, DateJump.target(2020, 1, start, end)) // 범위 이전
        assertEquals(end, DateJump.target(2026, 9, start, end)) // 범위 이후
    }
}

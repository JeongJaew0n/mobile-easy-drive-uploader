package com.jjw.easygallery.feature.gallery

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * 연·월만 골라도 "적용" 이 되게 하려면 그 단위가 기간으로 바뀌어야 한다.
 * 달력이 다루는 범위를 넘지 않는 것이 핵심이다.
 */
class DateJumpWholeRangeTest {

    private val start = YearMonth.of(2017, 3)
    private val end = YearMonth.of(2026, 9)

    @Test
    fun `가운데 해는 1월 1일부터 12월 31일까지`() {
        val r = DateJump.wholeYear(2020, start, end)
        assertEquals(LocalDate.of(2020, 1, 1), r.start)
        assertEquals(LocalDate.of(2020, 12, 31), r.endInclusive)
    }

    @Test
    fun `첫 해는 달력이 시작하는 달부터`() {
        // 2017-03 이전은 달력에 없다. 1월부터 잡으면 고를 수 없는 날이 범위에 들어간다
        val r = DateJump.wholeYear(2017, start, end)
        assertEquals(LocalDate.of(2017, 3, 1), r.start)
        assertEquals(LocalDate.of(2017, 12, 31), r.endInclusive)
    }

    @Test
    fun `올해는 달력 마지막 달의 말일에서 멈춘다`() {
        val r = DateJump.wholeYear(2026, start, end)
        assertEquals(LocalDate.of(2026, 1, 1), r.start)
        assertEquals(LocalDate.of(2026, 9, 30), r.endInclusive)
    }

    @Test
    fun `한 달은 1일부터 말일까지`() {
        val r = DateJump.wholeMonth(2024, 2, start, end)
        assertEquals(LocalDate.of(2024, 2, 1), r.start)
        // 윤년
        assertEquals(LocalDate.of(2024, 2, 29), r.endInclusive)
    }

    @Test
    fun `범위 밖 달은 가장 가까운 끝으로 당긴다`() {
        val r = DateJump.wholeMonth(2017, 1, start, end)
        assertEquals(LocalDate.of(2017, 3, 1), r.start)
        assertEquals(LocalDate.of(2017, 3, 31), r.endInclusive)
    }
}

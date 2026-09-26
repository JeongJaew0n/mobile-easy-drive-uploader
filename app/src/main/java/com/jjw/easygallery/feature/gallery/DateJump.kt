package com.jjw.easygallery.feature.gallery

import com.jjw.easygallery.core.domain.model.DateRange
import java.time.LocalDate
import java.time.YearMonth

/**
 * 기간 선택 시트의 연·월 빠른 이동 계산(순수 함수). `docs/plans/date-quick-jump`.
 * ML Kit·MediaStore 와 무관한 UI 보조 로직이라 여기서 테스트로 굳힌다.
 */
internal object DateJump {

    private const val JANUARY = 1
    private const val DECEMBER = 12

    /**
     * 달력이 다루는 [start]~[end] 안의 모든 연도. 내림차순(최신 먼저).
     * 사진이 없는 날도 고를 수 있으므로 그 자리로 **갈 수도** 있어야 한다 —
     * 사진 있는 해만 담으면 빈 해의 날짜를 지정할 방법이 없어진다.
     */
    fun yearsInRange(start: YearMonth, end: YearMonth): List<Int> =
        (end.year downTo start.year).toList()

    /** [year]·[month] 가 달력이 다루는 범위 안인지. 밖이면 눌러도 갈 곳이 없다 */
    fun inRange(year: Int, month: Int, start: YearMonth, end: YearMonth): Boolean =
        YearMonth.of(year, month) in start..end

    /** [year] 에서 사진이 하나라도 있는 달(1~12). 달력 범위 밖은 뺀다 */
    fun monthsWithPhotos(dayCounts: Map<LocalDate, Int>, year: Int, start: YearMonth, end: YearMonth): Set<Int> =
        dayCounts.keys.filter { it.year == year && YearMonth.from(it) in start..end }
            .mapTo(HashSet()) { it.monthValue }

    /**
     * [year]·[month] 로 점프할 대상. 달력이 다루는 [start]~[end] 범위를 벗어나면 가장 가까운 끝으로 당긴다.
     * ([inRange] 로 범위 밖 버튼을 이미 막지만, 경계는 방어적으로 한 번 더 막는다.)
     */
    fun target(year: Int, month: Int, start: YearMonth, end: YearMonth): YearMonth =
        YearMonth.of(year, month).coerceIn(start, end)

    /**
     * [year] 한 해 전체를 기간으로. 달력이 다루는 [start]~[end] 밖으로는 나가지 않는다 —
     * 올해를 고르면 아직 오지 않은 달까지 잡히는 대신 달력 마지막 달의 말일에서 멈춘다.
     */
    fun wholeYear(year: Int, start: YearMonth, end: YearMonth): DateRange {
        val first = YearMonth.of(year, JANUARY).coerceAtLeast(start)
        val last = YearMonth.of(year, DECEMBER).coerceAtMost(end)
        return DateRange(first.atDay(1), last.atEndOfMonth())
    }

    /** [year]·[month] 한 달 전체를 기간으로. 범위 밖이면 가장 가까운 끝으로 당긴다 */
    fun wholeMonth(year: Int, month: Int, start: YearMonth, end: YearMonth): DateRange {
        val target = target(year, month, start, end)
        return DateRange(target.atDay(1), target.atEndOfMonth())
    }
}

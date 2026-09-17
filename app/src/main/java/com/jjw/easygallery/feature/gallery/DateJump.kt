package com.jjw.easygallery.feature.gallery

import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs

/**
 * 기간 선택 시트의 연·월 빠른 이동 계산(순수 함수). `docs/plans/date-quick-jump`.
 * ML Kit·MediaStore 와 무관한 UI 보조 로직이라 여기서 테스트로 굳힌다.
 */
internal object DateJump {

    /**
     * 사진이 있는 연도들. 내림차순(최신 먼저).
     * 달력이 다루는 [start]~[end] 밖은 뺀다 — 기기 시계가 틀린 채 찍혔거나 타임스탬프가 미래인 파일이 있으면
     * 달력이 보여줄 수 없는 연도 칩이 생기고, 눌러도 아무 데도 안 간 것처럼 보인다.
     */
    fun yearsWithPhotos(dayCounts: Map<LocalDate, Int>, start: YearMonth, end: YearMonth): List<Int> =
        dayCounts.keys.filter { YearMonth.from(it) in start..end }
            .map { it.year }
            .distinct()
            .sortedDescending()

    /**
     * [wanted] 에 가장 가까운, **사진이 있는** 연도. 없으면 null.
     * 달력 범위는 연속이라 중간에 사진이 한 장도 없는 해가 낄 수 있는데, 그 해로 패널을 열면
     * 고를 수 있는 것이 하나도 없다. 그래서 여는 시점에 가장 가까운 해로 당긴다.
     */
    fun nearestYear(years: List<Int>, wanted: Int): Int? =
        years.minByOrNull { abs(it - wanted) }

    /** [year] 에서 사진이 하나라도 있는 달(1~12). 달력 범위 밖은 뺀다 */
    fun monthsWithPhotos(dayCounts: Map<LocalDate, Int>, year: Int, start: YearMonth, end: YearMonth): Set<Int> =
        dayCounts.keys.filter { it.year == year && YearMonth.from(it) in start..end }
            .mapTo(HashSet()) { it.monthValue }

    /**
     * [year]·[month] 로 점프할 대상. 달력이 다루는 [start]~[end] 범위를 벗어나면 가장 가까운 끝으로 당긴다.
     * (사진이 있는 달만 버튼이 활성이지만, 범위 경계는 방어적으로 한 번 더 막는다.)
     */
    fun target(year: Int, month: Int, start: YearMonth, end: YearMonth): YearMonth =
        YearMonth.of(year, month).coerceIn(start, end)
}

package com.jjw.easygallery.feature.gallery

import java.time.LocalDate
import java.time.YearMonth

/**
 * 기간 선택 시트의 연·월 빠른 이동 계산(순수 함수). `docs/plans/date-quick-jump`.
 * ML Kit·MediaStore 와 무관한 UI 보조 로직이라 여기서 테스트로 굳힌다.
 */
internal object DateJump {

    /** 사진이 있는 연도들. 내림차순(최신 먼저) */
    fun yearsWithPhotos(dayCounts: Map<LocalDate, Int>): List<Int> =
        dayCounts.keys.map { it.year }.distinct().sortedDescending()

    /** [year] 에서 사진이 하나라도 있는 달(1~12) */
    fun monthsWithPhotos(dayCounts: Map<LocalDate, Int>, year: Int): Set<Int> =
        dayCounts.keys.filter { it.year == year }.mapTo(HashSet()) { it.monthValue }

    /**
     * [year]·[month] 로 점프할 대상. 달력이 다루는 [start]~[end] 범위를 벗어나면 가장 가까운 끝으로 당긴다.
     * (사진이 있는 달만 버튼이 활성이지만, 범위 경계는 방어적으로 한 번 더 막는다.)
     */
    fun target(year: Int, month: Int, start: YearMonth, end: YearMonth): YearMonth =
        YearMonth.of(year, month).coerceIn(start, end)
}

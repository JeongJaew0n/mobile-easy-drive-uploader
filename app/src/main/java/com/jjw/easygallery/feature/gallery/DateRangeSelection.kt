package com.jjw.easygallery.feature.gallery

import com.jjw.easygallery.core.domain.model.DateRange
import java.time.LocalDate

/**
 * 달력 탭으로 기간을 고르는 순수 상태. 시작일 탭 → 종료일 탭.
 * - 시작일만 있을 때 그보다 앞선 날을 탭하면 시작일을 다시 잡는다
 * - 범위가 완성된 뒤 탭하면 새 시작일부터 다시
 * - 시작일만 있는 상태도 [toDateRange] 로 하루짜리 기간이 된다
 */
internal data class DateRangeSelection(
    val start: LocalDate? = null,
    val end: LocalDate? = null,
) {
    fun select(day: LocalDate): DateRangeSelection = when {
        start == null || end != null -> DateRangeSelection(start = day)
        day.isBefore(start) -> DateRangeSelection(start = day)
        else -> DateRangeSelection(start = start, end = day)
    }

    fun toDateRange(): DateRange? = start?.let { DateRange(it, end ?: it) }

    fun contains(day: LocalDate): Boolean {
        val range = toDateRange() ?: return false
        return !day.isBefore(range.start) && !day.isAfter(range.endInclusive)
    }

    companion object {
        fun of(range: DateRange?): DateRangeSelection =
            if (range == null) DateRangeSelection() else DateRangeSelection(range.start, range.endInclusive)
    }
}

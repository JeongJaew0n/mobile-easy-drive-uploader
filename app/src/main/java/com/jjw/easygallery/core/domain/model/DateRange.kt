package com.jjw.easygallery.core.domain.model

import java.time.LocalDate
import java.time.ZoneId

/** 촬영 날짜 기준 조회 기간. 양 끝 날짜를 포함한다. */
data class DateRange(
    val start: LocalDate,
    val endInclusive: LocalDate,
) {
    init {
        require(!endInclusive.isBefore(start)) { "종료일이 시작일보다 빠를 수 없습니다" }
    }

    fun startMillis(zone: ZoneId = ZoneId.systemDefault()): Long =
        start.atStartOfDay(zone).toInstant().toEpochMilli()

    /** 종료일 다음 날 0시 — 비교는 [startMillis] 이상, 이 값 미만 */
    fun endExclusiveMillis(zone: ZoneId = ZoneId.systemDefault()): Long =
        endInclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    val isSingleDay: Boolean get() = start == endInclusive

    companion object {
        fun of(startEpochDay: Long, endEpochDay: Long): DateRange =
            DateRange(LocalDate.ofEpochDay(startEpochDay), LocalDate.ofEpochDay(endEpochDay))
    }
}

/** 기간 선택 다이얼로그의 빠른 선택지 */
enum class DatePreset {
    TODAY,
    LAST_7_DAYS,
    LAST_30_DAYS,
    THIS_YEAR,
    ;

    fun toRange(today: LocalDate): DateRange = when (this) {
        TODAY -> DateRange(today, today)
        // 오늘을 포함해서 센다
        LAST_7_DAYS -> DateRange(today.minusDays(WEEK_DAYS - 1), today)
        LAST_30_DAYS -> DateRange(today.minusDays(MONTH_DAYS - 1), today)
        THIS_YEAR -> DateRange(today.withDayOfYear(1), today)
    }

    private companion object {
        const val WEEK_DAYS = 7L
        const val MONTH_DAYS = 30L
    }
}

/** [range] 가 null 이면 그대로 반환한다. 경계값은 한 번만 계산한다. */
fun List<MediaItem>.filterByDate(range: DateRange?, zone: ZoneId = ZoneId.systemDefault()): List<MediaItem> {
    if (range == null) return this
    val from = range.startMillis(zone)
    val until = range.endExclusiveMillis(zone)
    return filter { it.dateTakenMillis in from until until }
}

package com.jjw.easygallery.feature.gallery

import com.jjw.easygallery.core.domain.model.MediaItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 같은 날짜에 촬영된 항목 묶음. 최신 날짜가 먼저 온다. */
data class GallerySection(
    val date: LocalDate,
    val items: List<MediaItem>,
)

/**
 * 날짜 묶음 전체를 선택/해제한다. 이미 전부 선택돼 있으면 해제, 아니면(일부·없음) 전부 선택.
 */
internal fun Set<Long>.toggleSection(sectionIds: List<Long>): Set<Long> =
    if (sectionIds.isNotEmpty() && sectionIds.all { it in this }) this - sectionIds.toSet() else this + sectionIds

/** [items] 는 최신순으로 정렬되어 있다고 가정한다. 섹션 내 순서는 입력 순서를 유지한다. */
fun groupByDate(items: List<MediaItem>, zone: ZoneId = ZoneId.systemDefault()): List<GallerySection> {
    if (items.isEmpty()) return emptyList()
    val sections = ArrayList<GallerySection>()
    var currentDate: LocalDate? = null
    var bucket = ArrayList<MediaItem>()
    for (item in items) {
        val date = Instant.ofEpochMilli(item.dateTakenMillis).atZone(zone).toLocalDate()
        if (date != currentDate) {
            if (currentDate != null) sections += GallerySection(currentDate, bucket)
            currentDate = date
            bucket = ArrayList()
        }
        bucket += item
    }
    currentDate?.let { sections += GallerySection(it, bucket) }
    return sections
}

/** 날짜별 항목 수. 기간 선택 달력에서 사진이 있는 날을 표시하는 데 쓴다(기간 필터 이전 목록 기준) */
fun countByDay(items: List<MediaItem>, zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, Int> {
    val counts = HashMap<LocalDate, Int>()
    for (item in items) {
        val date = Instant.ofEpochMilli(item.dateTakenMillis).atZone(zone).toLocalDate()
        counts[date] = (counts[date] ?: 0) + 1
    }
    return counts
}

package com.jjw.easygallery.feature.gallery

import com.jjw.easygallery.core.domain.model.MediaItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 묶음 머리글. 갤러리는 날짜로 묶지만 '다른 앱' 탭은 앱(폴더)으로 묶는다 */
sealed interface SectionHeader {
    data class ByDate(val date: LocalDate) : SectionHeader

    /**
     * [folder] 는 `relativePath` 에서 뽑은 폴더 이름 원문(`KakaoTalk`).
     * 화면에 보일 때만 [AppFolders.displayNameRes] 로 한국어를 찾는다 — 표에 없으면 원문 그대로다.
     */
    data class ByApp(val folder: String) : SectionHeader
}

/** 머리글 하나와 그 아래 항목들. 항목 순서는 입력(최신순)을 그대로 따른다 */
data class GallerySection(
    val header: SectionHeader,
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
            if (currentDate != null) sections += GallerySection(SectionHeader.ByDate(currentDate), bucket)
            currentDate = date
            bucket = ArrayList()
        }
        bucket += item
    }
    currentDate?.let { sections += GallerySection(SectionHeader.ByDate(it), bucket) }
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

/**
 * 앱(폴더)별로 묶는다. '다른 앱' 탭에서 날짜 대신 쓴다 — 카카오톡 사진 1000여 장이
 * 날짜순으로 흩어져 있으면 어느 앱 것인지 알아볼 수 없다.
 *
 * 묶음은 **항목이 많은 순**으로, 같으면 이름순. 묶음 안 순서는 입력(최신순) 그대로다.
 * 폴더를 알 수 없는 항목(경로가 빈 레거시 행)은 맨 뒤 한 묶음으로 모은다.
 */
fun groupByApp(items: List<MediaItem>): List<GallerySection> {
    if (items.isEmpty()) return emptyList()
    val buckets = LinkedHashMap<String, MutableList<MediaItem>>()
    for (item in items) {
        val folder = AppFolders.folderOf(item.relativePath) ?: UNKNOWN_FOLDER
        buckets.getOrPut(folder) { ArrayList() } += item
    }
    return buckets.entries
        .sortedWith(compareByDescending<Map.Entry<String, List<MediaItem>>> { it.value.size }.thenBy { it.key })
        .map { (folder, group) -> GallerySection(SectionHeader.ByApp(folder), group) }
}

/** 경로가 없어 앱을 알 수 없는 항목의 묶음 이름 */
internal const val UNKNOWN_FOLDER = "?"

package com.jjw.easygallery.feature.drive

import com.jjw.easygallery.core.domain.model.DriveEntry
import com.jjw.easygallery.core.domain.model.DriveMediaOrder
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Drive 사진 거르기(`docs/plans/drive-photos/spec.md` §3.2). 영상은 서버가, 기기에 없음은 기기가 거른다 */
enum class DrivePhotoFilter { ALL, NOT_ON_DEVICE, VIDEOS }

/** 격자의 한 칸 — 달 머리글 또는 사진 */
sealed interface DrivePhotoCell {
    val key: String

    data class Header(val month: YearMonth?) : DrivePhotoCell {
        override val key: String get() = "header:$month"
    }

    data class Photo(val entry: DriveEntry) : DrivePhotoCell {
        override val key: String get() = entry.id
    }
}

/**
 * 원장(원격 ID → 기기 사진 ID)과 지금 기기의 사진으로 "이 기기에도 있는" Drive 파일을 가린다.
 * 원장에 없는 파일(다른 기기에서 올렸거나 재설치 전에 올린 것)은 기기에 없는 것으로 본다 — 사진 ID 는 기기마다 달라
 * 파일에 적힌 ID 로는 판단할 수 없다.
 */
internal fun onDeviceRemoteIds(remoteToMedia: Map<String, Long>, deviceIds: Set<Long>): Set<String> =
    remoteToMedia.filterValues { it in deviceIds }.keys

/**
 * 순서의 기준 시각. 찍은 날짜순은 EXIF → **파일 이름의 시각** 순으로 찾고, 둘 다 없으면 null("날짜 없음", 맨 뒤).
 *
 * 올린 시각으로 대신하지 않는다 — 2026-10-08 기기에서 카카오톡에서 받은 2015년 사진(EXIF 없음, 이름이 `1435487647130.jpeg`)
 * 수천 장이 "2026년 9월"(올린 달) 묶음에 들어가, 정작 9월에 찍은 사진이 그 뒤로 밀렸다.
 */
internal fun DriveEntry.sortTime(order: DriveMediaOrder): Long? = when (order) {
    DriveMediaOrder.UPLOADED -> createdTimeMillis ?: modifiedTimeMillis
    DriveMediaOrder.TAKEN -> takenTimeMillis ?: timeFromFileName(name)
}

/**
 * 파일 이름에 든 촬영 시각. 카메라·스크린샷의 `20230808_122540`(앞뒤 접두사 허용, `-` 도)과
 * 카카오톡 등의 13자리 밀리초(`1435487647130`)를 안다. 기기 시간대로 읽는다(EXIF 와 같은 규칙). 그럴듯하지 않으면 null
 */
internal fun timeFromFileName(name: String, zone: ZoneId = ZoneId.systemDefault()): Long? {
    STAMP.find(name)?.let { match ->
        // 20230808_122540 → 2023-08-08T12:25:40. 13월 같은 것은 파싱이 거른다
        val text = match.groupValues.drop(1).joinToString("")
        return runCatching {
            LocalDateTime.parse(text, STAMP_FORMAT).atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()
    }
    val millis = EPOCH_MILLIS.find(name)?.value?.toLongOrNull() ?: return null
    return millis.takeIf { it in EPOCH_MIN..EPOCH_MAX }
}

private val STAMP = Regex("(?<!\\d)((?:19|20)\\d{2})(\\d{2})(\\d{2})[_-](\\d{2})(\\d{2})(\\d{2})(?!\\d)")
private val STAMP_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT)
private val EPOCH_MILLIS = Regex("(?<!\\d)\\d{13}(?!\\d)")

// 2001-09-09 ~ 2286 — 13자리 밀리초가 뜻 있는 범위. 그 밖의 13자리 숫자는 시각이 아니다
private const val EPOCH_MIN = 1_000_000_000_000L
private const val EPOCH_MAX = 9_999_999_999_999L

/** 서버는 올린 시각순으로 준다. 찍은 날짜순은 기기에서 다시 정렬한다(시각을 모르는 것은 맨 뒤) */
internal fun List<DriveEntry>.sortedFor(order: DriveMediaOrder): List<DriveEntry> = when (order) {
    DriveMediaOrder.UPLOADED -> this
    DriveMediaOrder.TAKEN -> sortedWith(compareByDescending<DriveEntry, Long?>(nullsFirst()) { it.sortTime(order) })
}

/** 다음 쪽을 잇는다. 서버 순서를 그대로 두고, 이미 있는 것(쪽 경계에서 겹친 것)은 뺀다 */
internal fun List<DriveEntry>.appendNew(more: List<DriveEntry>): List<DriveEntry> {
    if (more.isEmpty()) return this
    val present = mapTo(HashSet()) { it.id }
    return this + more.filterNot { it.id in present }
}

internal fun List<DriveEntry>.filteredBy(filter: DrivePhotoFilter, onDeviceIds: Set<String>): List<DriveEntry> =
    when (filter) {
        DrivePhotoFilter.ALL -> this
        DrivePhotoFilter.NOT_ON_DEVICE -> filterNot { it.id in onDeviceIds }
        DrivePhotoFilter.VIDEOS -> filter { it.isVideo }
    }

/** 이미 정렬된 목록을 달별로 끊어 머리글을 끼운다. 시각을 모르는 것은 "날짜 없음" 한 묶음 */
internal fun List<DriveEntry>.toCells(
    order: DriveMediaOrder,
    zone: ZoneId = ZoneId.systemDefault(),
): List<DrivePhotoCell> {
    val cells = ArrayList<DrivePhotoCell>(size + size / AVERAGE_PER_MONTH + 1)
    var current: YearMonth? = null
    var first = true
    forEach { entry ->
        val month = entry.sortTime(order)?.let { YearMonth.from(Instant.ofEpochMilli(it).atZone(zone)) }
        if (first || month != current) {
            cells += DrivePhotoCell.Header(month)
            current = month
            first = false
        }
        cells += DrivePhotoCell.Photo(entry)
    }
    return cells
}

private const val AVERAGE_PER_MONTH = 30

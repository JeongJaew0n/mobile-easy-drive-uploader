package com.jjw.easygallery.feature.gallery

import com.jjw.easygallery.R
import com.jjw.easygallery.core.data.upload.BackupDestinations
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.domain.model.RemoteAccount
import com.jjw.easygallery.core.domain.model.UploadRecord
import com.jjw.easygallery.core.domain.model.UploadState
import com.jjw.easygallery.core.domain.model.UploadTask
import com.jjw.easygallery.feature.uploads.uploadFailureRes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 백업된 사진 화면의 세그먼트(`docs/plans/backed-up-photos/spec.md` §2.2) */
enum class BackupStatusFilter { BACKED_UP, NOT_BACKED_UP, PENDING_OR_FAILED }

/** 백업된 사진 화면에서 지금 보는 것. [destination] null 이면 모든 저장소 */
data class BackupView(
    val status: BackupStatusFilter = BackupStatusFilter.BACKED_UP,
    val destination: String? = null,
)

/** 묶기에 필요한 원장·큐 — 백업 범위일 때만 모은다 */
data class BackupInputs(
    val view: BackupView,
    val records: List<UploadRecord>,
    val tasks: List<UploadTask>,
    val primaryEmail: String?,
    val accounts: List<RemoteAccount>,
)

/**
 * 백업 범위의 목록을 묶는다. [items] 는 숨김·카테고리·기간을 이미 거른 기기 사진(찍은 날짜 최신순).
 * 화면의 격자와 상세보기의 좌우 넘기기가 **같은 순서**를 쓰도록 둘 다 이 함수로 만든다.
 *
 * - 백업됨: 고른 저장소(없으면 어디든)에 올라간 것. 가장 최근에 올린 시각순, 올린 날짜로 묶는다
 * - 안 됨: 고른 저장소(없으면 어디에도)에 안 올라간 것. 찍은 날짜로 묶는다
 * - 대기·실패: 큐의 대기·진행 중 한 묶음, 실패는 까닭별(많은 것부터). [uploadedAtTarget] 에 있는 실패 줄은 뺀다 —
 *   옛 배치의 실패 줄이 남아 있어도 원장에 있으면 올라간 것이다(썸네일 배지와 같은 규칙)
 */
internal fun backupSections(
    inputs: BackupInputs,
    items: List<MediaItem>,
    uploadedAtTarget: Set<Long>,
    zone: ZoneId = ZoneId.systemDefault(),
): List<GallerySection> {
    val view = inputs.view
    val records = inputs.records.filter { record ->
        view.destination == null ||
            BackupDestinations.canonical(record.destination, inputs.primaryEmail) == view.destination
    }
    return when (view.status) {
        BackupStatusFilter.BACKED_UP -> backedUpSections(items, records, zone)
        BackupStatusFilter.NOT_BACKED_UP -> {
            val uploaded = records.mapTo(HashSet()) { it.mediaId }
            groupByDate(items.filterNot { it.id in uploaded }, zone)
        }
        BackupStatusFilter.PENDING_OR_FAILED -> queueSections(items, inputs.tasks, uploadedAtTarget)
    }
}

private fun backedUpSections(items: List<MediaItem>, records: List<UploadRecord>, zone: ZoneId): List<GallerySection> {
    val latest = HashMap<Long, Long>()
    records.forEach { record -> latest.merge(record.mediaId, record.uploadedAt, ::maxOf) }
    val ordered = items.filter { it.id in latest }.sortedByDescending { latest.getValue(it.id) }
    val sections = ArrayList<GallerySection>()
    var currentDate: LocalDate? = null
    var bucket = ArrayList<MediaItem>()
    for (item in ordered) {
        val date = Instant.ofEpochMilli(latest.getValue(item.id)).atZone(zone).toLocalDate()
        if (date != currentDate) {
            currentDate?.let { sections += GallerySection(SectionHeader.ByUploadDate(it), bucket) }
            currentDate = date
            bucket = ArrayList()
        }
        bucket += item
    }
    currentDate?.let { sections += GallerySection(SectionHeader.ByUploadDate(it), bucket) }
    return sections
}

private fun queueSections(
    items: List<MediaItem>,
    tasks: List<UploadTask>,
    uploadedAtTarget: Set<Long>,
): List<GallerySection> {
    val pendingIds = tasks.filter { it.isActive }.mapTo(HashSet()) { it.mediaId }
    val failureOf = tasks
        .filter { it.state == UploadState.FAILED && it.mediaId !in pendingIds && it.mediaId !in uploadedAtTarget }
        .associate { it.mediaId to (uploadFailureRes(it.errorReason) ?: R.string.upload_state_failed) }
    val pending = items.filter { it.id in pendingIds }
    val failedGroups = items.filter { it.id in failureOf }
        .groupBy { failureOf.getValue(it.id) }
        .entries
        .sortedByDescending { it.value.size }
    return buildList {
        if (pending.isNotEmpty()) add(GallerySection(SectionHeader.ByUploadStatus(pending = true), pending))
        failedGroups.forEach { (res, group) ->
            add(GallerySection(SectionHeader.ByUploadStatus(pending = false, failureRes = res), group))
        }
    }
}

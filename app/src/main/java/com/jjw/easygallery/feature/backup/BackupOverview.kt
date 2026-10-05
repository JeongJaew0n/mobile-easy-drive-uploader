package com.jjw.easygallery.feature.backup

import com.jjw.easygallery.core.data.upload.BackupDestinations
import com.jjw.easygallery.core.domain.model.BackupDestination
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.domain.model.RemoteAccount
import com.jjw.easygallery.core.domain.model.UploadRecord

/**
 * 백업 칸 요약 카드(`docs/plans/backed-up-photos/spec.md` §2.1).
 * [backedUpCount] 는 **어디든** 한 곳에라도 올라간 기기 사진 수. 저장소별 수는 서로 겹칠 수 있다(두 곳에 올린 사진).
 */
data class BackupOverview(
    val deviceCount: Int,
    val backedUpCount: Int,
    val perDestination: List<DestinationCount>,
    /** 마지막으로 올린 기기 사진들(최근 것부터) */
    val recent: List<MediaItem>,
) {
    val percent: Int get() = if (deviceCount == 0) 0 else backedUpCount * PERCENT / deviceCount

    private companion object {
        const val PERCENT = 100
    }
}

data class DestinationCount(val destination: BackupDestination, val count: Int)

/** [visible] 은 숨김을 뺀 기기 사진. 기기에 없는 사진의 원장 줄은 세지 않는다 — 카드는 "이 기기의" 사진을 말한다 */
internal fun backupOverview(
    visible: List<MediaItem>,
    records: List<UploadRecord>,
    accounts: List<RemoteAccount>,
    primaryEmail: String?,
    recentLimit: Int = RECENT_LIMIT,
): BackupOverview {
    val byId = visible.associateBy { it.id }
    val onDevice = records.filter { it.mediaId in byId }
    val latest = HashMap<Long, Long>()
    onDevice.forEach { latest.merge(it.mediaId, it.uploadedAt, ::maxOf) }
    val perDestination = onDevice
        .groupBy { BackupDestinations.canonical(it.destination, primaryEmail) }
        .mapValues { (_, rows) -> rows.mapTo(HashSet()) { it.mediaId }.size }
    val ordered = BackupDestinations.ordered(perDestination.keys, accounts, primaryEmail)
    return BackupOverview(
        deviceCount = visible.size,
        backedUpCount = latest.size,
        perDestination = ordered.map { DestinationCount(it, perDestination.getValue(it.destination)) },
        recent = latest.entries.sortedByDescending { it.value }.take(recentLimit).map { byId.getValue(it.key) },
    )
}

private const val RECENT_LIMIT = 8

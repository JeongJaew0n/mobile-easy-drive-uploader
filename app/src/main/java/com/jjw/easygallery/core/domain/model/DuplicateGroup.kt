package com.jjw.easygallery.core.domain.model

/** 바이트 단위로 완전히 같은 파일 묶음. [keepId] 는 기본으로 남길 항목. */
data class DuplicateGroup(
    val sha256: String,
    val items: List<MediaItem>,
    val keepId: Long,
) {
    val duplicateCount: Int get() = items.size - 1
    val wastedBytes: Long get() = items.first().sizeBytes * duplicateCount
    val removeIds: List<Long> get() = items.map { it.id }.filter { it != keepId }
}

/**
 * 해시가 같은 항목이 2개 이상이면 그룹으로 묶는다.
 * 남길 항목: Drive 에 백업된 것 → 가장 먼저 저장된 것(DATE_ADDED) → 가장 작은 id.
 * 그룹은 낭비 용량이 큰 순, 그룹 안은 유지 항목이 먼저.
 */
fun findDuplicateGroups(
    items: List<MediaItem>,
    hashById: Map<Long, String>,
    uploadedIds: Set<Long> = emptySet(),
): List<DuplicateGroup> {
    val byHash = HashMap<String, MutableList<MediaItem>>()
    for (item in items) {
        val hash = hashById[item.id] ?: continue
        byHash.getOrPut(hash) { ArrayList() }.add(item)
    }
    return byHash.entries
        .filter { it.value.size > 1 }
        .map { (hash, group) ->
            val keep = group.sortedWith(
                compareByDescending<MediaItem> { it.id in uploadedIds }
                    .thenBy { it.dateAddedSeconds }
                    .thenBy { it.id },
            ).first()
            DuplicateGroup(
                sha256 = hash,
                items = listOf(keep) + group.filter { it.id != keep.id }.sortedBy { it.dateAddedSeconds },
                keepId = keep.id,
            )
        }
        .sortedWith(compareByDescending<DuplicateGroup> { it.wastedBytes }.thenBy { it.sha256 })
}

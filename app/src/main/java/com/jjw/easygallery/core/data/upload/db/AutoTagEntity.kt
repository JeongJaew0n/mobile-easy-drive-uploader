package com.jjw.easygallery.core.data.upload.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 기계가 붙인 라벨. 사용자가 만든 카테고리(`media_category`)와 **일부러 분리**한다
 * (`docs/AUTO_TAGGING.md` §2). 한 사진에 여러 라벨이 붙는다.
 */
@Entity(
    tableName = "auto_tag",
    primaryKeys = ["mediaId", "label"],
    indices = [Index("label")],
)
data class AutoTagEntity(
    val mediaId: Long,
    /** ML Kit 이 돌려주는 영어 라벨 원문(예: "Food"). 표시 이름은 화면에서 대응표로 바꾼다 */
    val label: String,
    /** 0.0~1.0 */
    val confidence: Float,
    val taggedAt: Long,
)

/**
 * 분석 시도 기록. 라벨이 하나도 안 나온 사진도 남겨 매번 다시 돌리지 않는다.
 * 크기·수정 시각이 그대로면 건너뛴다(중복 검사 캐시와 같은 방식).
 */
@Entity(tableName = "auto_tag_scan")
data class AutoTagScanEntity(
    @PrimaryKey val mediaId: Long,
    val sizeBytes: Long,
    val dateModifiedSeconds: Long,
    val scannedAt: Long,
    /**
     * 연속 실패 횟수. 0 = 분석 성공.
     * 1~2 는 다음 훑기에서 다시 시도하고, [AutoTagScanEntity.MAX_FAILURES] 이상이면 포기한다
     * — 깨진 파일 하나 때문에 매번 헛도는 것을 막는다. 파일이 바뀌면 다시 1부터 센다.
     */
    @ColumnInfo(defaultValue = "0")
    val failureCount: Int = 0,
) {
    companion object {
        /** 이 횟수만큼 실패하면 더 시도하지 않는다("자동 태그 전부 지우기" 로 초기화된다) */
        const val MAX_FAILURES = 3
    }
}

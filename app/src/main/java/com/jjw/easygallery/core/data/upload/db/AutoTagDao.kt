package com.jjw.easygallery.core.data.upload.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** 라벨 하나와 그 라벨이 붙은 사진 수 */
data class AutoTagCount(val label: String, val count: Int)

@Dao
abstract class AutoTagDao {

    @Query("SELECT label, COUNT(*) AS count FROM auto_tag GROUP BY label ORDER BY count DESC, label ASC")
    abstract fun observeCounts(): Flow<List<AutoTagCount>>

    @Query("SELECT mediaId FROM auto_tag WHERE label = :label")
    abstract fun observeMediaIds(label: String): Flow<List<Long>>

    @Query("SELECT mediaId FROM auto_tag WHERE label = :label")
    abstract suspend fun mediaIdsOf(label: String): List<Long>

    @Query("SELECT * FROM auto_tag_scan")
    abstract suspend fun allScans(): List<AutoTagScanEntity>

    @Query("SELECT COUNT(*) FROM auto_tag_scan")
    abstract fun observeScannedCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertTags(tags: List<AutoTagEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertScan(scan: AutoTagScanEntity)

    @Query("DELETE FROM auto_tag WHERE mediaId = :mediaId")
    abstract suspend fun deleteTagsOf(mediaId: Long)

    @Query("DELETE FROM auto_tag WHERE mediaId IN (:mediaIds)")
    abstract suspend fun deleteTagsOfAll(mediaIds: List<Long>)

    @Query("DELETE FROM auto_tag_scan WHERE mediaId IN (:mediaIds)")
    abstract suspend fun deleteScansOf(mediaIds: List<Long>)

    @Query("DELETE FROM auto_tag")
    abstract suspend fun clearTags()

    @Query("DELETE FROM auto_tag_scan")
    abstract suspend fun clearScans()

    /** 한 사진의 분석 결과를 통째로 바꾼다 — 다시 분석하면 옛 라벨이 남으면 안 된다 */
    @Transaction
    open suspend fun replaceFor(mediaId: Long, tags: List<AutoTagEntity>, scan: AutoTagScanEntity) {
        deleteTagsOf(mediaId)
        if (tags.isNotEmpty()) insertTags(tags)
        insertScan(scan)
    }

    /** 갤러리에서 사라진 사진의 흔적을 지운다 */
    @Transaction
    open suspend fun prune(mediaIds: List<Long>) {
        if (mediaIds.isEmpty()) return
        mediaIds.chunked(PRUNE_CHUNK).forEach { chunk ->
            deleteTagsOfAll(chunk)
            deleteScansOf(chunk)
        }
    }

    @Transaction
    open suspend fun clearAll() {
        clearTags()
        clearScans()
    }

    private companion object {
        /** SQLite 의 변수 개수 제한(999)을 넘지 않게 나눠 지운다 */
        const val PRUNE_CHUNK = 500
    }
}

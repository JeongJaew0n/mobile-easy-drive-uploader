package com.jjw.easygallery.core.data.upload.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 원장 조회. "어디에 올렸나" 는 모두 [UploadedMediaEntity.destination] 으로 가린다 —
 * 호출 쪽의 `accountId` 를 `destination` 으로 바꾸는 것은 `UploadLedgerRepository` 가 한다.
 */
@Dao
interface UploadedMediaDao {

    /** 같은 사진을 같은 곳에 다시 올렸으면 최신 것으로 바꾼다. 다른 곳이면 **따로 남는다** */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: UploadedMediaEntity)

    /** 어디든 한 번이라도 올라간 것(중복 정리처럼 "어디든 백업됨" 이 기준일 때). 한 사진이 여러 줄일 수 있다 */
    @Query("SELECT DISTINCT mediaId FROM uploaded_media WHERE mediaId IN (:mediaIds)")
    suspend fun uploadedAmong(mediaIds: List<Long>): List<Long>

    /** [destination] 에 올라간 것만 (SQLite 변수 한도 때문에 호출 측에서 청크로 나눔) */
    @Query("SELECT mediaId FROM uploaded_media WHERE destination = :destination AND mediaId IN (:mediaIds)")
    suspend fun uploadedAmongAt(mediaIds: List<Long>, destination: String): List<Long>

    @Query("SELECT DISTINCT mediaId FROM uploaded_media")
    fun observeUploadedIds(): Flow<List<Long>>

    @Query("SELECT mediaId FROM uploaded_media WHERE destination = :destination")
    fun observeUploadedIdsAt(destination: String): Flow<List<Long>>

    /** 이 기기에서 그곳으로 올린 원격 파일 ID(Drive fileId / S3 키 / 경로) — 브라우저 "이 기기에서 올림" 표시 */
    @Query("SELECT driveFileId FROM uploaded_media WHERE destination = :destination")
    fun observeRemoteIdsAt(destination: String): Flow<List<String>>

    /**
     * 주인 미정인 옛 Drive 기록(스키마 11 이전)을 [destination] 에게 준다.
     *
     * 같은 사진이 이미 그 계정 이름으로 적혀 있으면 REPLACE 로 하나만 남긴다. 옮길 것이 없으면
     * 0행이라 몇 번 불러도 같다(`docs/plans/ledger-per-account/spec.md` §2.3).
     */
    @Query("UPDATE OR REPLACE uploaded_media SET destination = :destination WHERE destination = :unclaimed")
    suspend fun claimUnclaimed(destination: String, unclaimed: String = UNCLAIMED_DRIVE_DESTINATION)

    @Query("SELECT COUNT(*) FROM uploaded_media")
    suspend fun count(): Int

    @Query("DELETE FROM uploaded_media")
    suspend fun clear()
}

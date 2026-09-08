package com.jjw.easygallery.core.data.upload.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UploadedMediaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: UploadedMediaEntity)

    /** 주어진 ID 중 이미 올라간 것만 돌려준다 (SQLite 변수 한도 때문에 호출 측에서 청크로 나눔) */
    @Query("SELECT mediaId FROM uploaded_media WHERE mediaId IN (:mediaIds)")
    suspend fun uploadedAmong(mediaIds: List<Long>): List<Long>

    /** 특정 계정에 올라간 것만. [accountId] null 은 Google Drive(기존 행 포함) */
    @Query(
        """SELECT mediaId FROM uploaded_media WHERE mediaId IN (:mediaIds)
           AND ((:accountId IS NULL AND accountId IS NULL) OR accountId = :accountId)""",
    )
    suspend fun uploadedAmongForAccount(mediaIds: List<Long>, accountId: String?): List<Long>

    @Query("SELECT mediaId FROM uploaded_media")
    fun observeUploadedIds(): Flow<List<Long>>

    @Query(
        """SELECT mediaId FROM uploaded_media
           WHERE (:accountId IS NULL AND accountId IS NULL) OR accountId = :accountId""",
    )
    fun observeUploadedIdsForAccount(accountId: String?): Flow<List<Long>>

    @Query("SELECT COUNT(*) FROM uploaded_media")
    suspend fun count(): Int

    @Query("DELETE FROM uploaded_media")
    suspend fun clear()
}

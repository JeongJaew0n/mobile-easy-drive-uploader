package com.jjw.easygallery.core.data.upload.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HiddenMediaDao {

    @Query("SELECT mediaId FROM hidden_media")
    fun observeIds(): Flow<List<Long>>

    @Query("SELECT mediaId FROM hidden_media")
    suspend fun ids(): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun hide(rows: List<HiddenMediaEntity>)

    @Query("DELETE FROM hidden_media WHERE mediaId IN (:mediaIds)")
    suspend fun unhide(mediaIds: List<Long>)

    /** 갤러리에서 사라진(영구 삭제된) 사진의 기록을 지운다 */
    @Query("DELETE FROM hidden_media WHERE mediaId IN (:mediaIds)")
    suspend fun prune(mediaIds: List<Long>)
}

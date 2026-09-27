package com.jjw.easygallery.core.data.upload.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChosenMediaDao {

    @Query("SELECT mediaId FROM chosen_media")
    fun observeIds(): Flow<List<Long>>

    /** 이미 고른 것을 다시 넣으면 고른 시각만 새로 적힌다 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun choose(rows: List<ChosenMediaEntity>)

    @Query("DELETE FROM chosen_media WHERE mediaId IN (:mediaIds)")
    suspend fun unchoose(mediaIds: List<Long>)
}

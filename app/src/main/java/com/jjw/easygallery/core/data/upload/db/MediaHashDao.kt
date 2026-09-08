package com.jjw.easygallery.core.data.upload.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaHashDao {

    @Query("SELECT * FROM media_hash")
    fun observeAll(): Flow<List<MediaHashEntity>>

    @Query("SELECT * FROM media_hash")
    suspend fun getAll(): List<MediaHashEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MediaHashEntity)

    @Query("DELETE FROM media_hash WHERE mediaId IN (:mediaIds)")
    suspend fun deleteByIds(mediaIds: List<Long>)

    @Query("DELETE FROM media_hash")
    suspend fun clear()
}

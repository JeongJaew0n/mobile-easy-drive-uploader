package com.jjw.easygallery.core.data.upload.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RemoteAccountDao {
    @Query("SELECT * FROM remote_account ORDER BY createdAt")
    fun observeAll(): Flow<List<RemoteAccountEntity>>

    @Query("SELECT * FROM remote_account WHERE id = :id")
    suspend fun getById(id: String): RemoteAccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RemoteAccountEntity)

    @Query("UPDATE remote_account SET displayName = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("DELETE FROM remote_account WHERE id = :id")
    suspend fun delete(id: String)
}

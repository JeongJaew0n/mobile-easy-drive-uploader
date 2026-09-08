package com.jjw.easygallery.core.data.upload.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UploadTaskDao {

    @Query("SELECT * FROM upload_tasks ORDER BY createdAt, id")
    fun observeAll(): Flow<List<UploadTaskEntity>>

    /** RUNNING 도 포함 — 프로세스가 죽어 남은 항목을 이어서 처리한다. */
    @Query("SELECT * FROM upload_tasks WHERE state IN ('PENDING', 'RUNNING') ORDER BY createdAt, id LIMIT 1")
    suspend fun nextUnfinished(): UploadTaskEntity?

    @Query("SELECT COUNT(*) FROM upload_tasks WHERE state IN ('PENDING', 'RUNNING')")
    suspend fun countUnfinished(): Int

    @Query("SELECT mediaId FROM upload_tasks WHERE state IN ('PENDING', 'RUNNING')")
    suspend fun unfinishedMediaIds(): List<Long>

    /** 상태 무관하게 큐에 있는 것 — 자동 백업이 실패한 항목을 매 스캔마다 다시 넣지 않도록 */
    @Query("SELECT mediaId FROM upload_tasks WHERE mediaId IN (:mediaIds)")
    suspend fun queuedAmong(mediaIds: List<Long>): List<Long>

    @Insert
    suspend fun insertAll(tasks: List<UploadTaskEntity>): List<Long>

    @Query("UPDATE upload_tasks SET state = :state, attemptCount = :attemptCount, updatedAt = :now WHERE id = :id")
    suspend fun updateState(id: Long, state: String, attemptCount: Int, now: Long)

    @Query("UPDATE upload_tasks SET sessionUri = :sessionUri, bytesUploaded = 0, updatedAt = :now WHERE id = :id")
    suspend fun updateSession(id: Long, sessionUri: String?, now: Long)

    @Query("UPDATE upload_tasks SET folderId = :folderId, folderName = :folderName, updatedAt = :now WHERE id = :id")
    suspend fun updateFolder(id: Long, folderId: String, folderName: String, now: Long)

    @Query("UPDATE upload_tasks SET bytesUploaded = :bytes, updatedAt = :now WHERE id = :id")
    suspend fun updateProgress(id: Long, bytes: Long, now: Long)

    @Query(
        "UPDATE upload_tasks SET state = 'COMPLETED', driveFileId = :fileId, bytesUploaded = sizeBytes, " +
            "errorMessage = NULL, updatedAt = :now WHERE id = :id",
    )
    suspend fun markCompleted(id: Long, fileId: String, now: Long)

    @Query("UPDATE upload_tasks SET state = 'FAILED', errorMessage = :message, updatedAt = :now WHERE id = :id")
    suspend fun markFailed(id: Long, message: String?, now: Long)

    @Query(
        "UPDATE upload_tasks SET state = 'PENDING', errorMessage = NULL, attemptCount = 0, updatedAt = :now " +
            "WHERE state = 'FAILED'",
    )
    suspend fun retryFailed(now: Long): Int

    @Query("DELETE FROM upload_tasks WHERE state = 'COMPLETED'")
    suspend fun deleteCompleted(): Int

    @Query("DELETE FROM upload_tasks WHERE state IN ('PENDING', 'RUNNING')")
    suspend fun deleteUnfinished(): Int

    @Query("DELETE FROM upload_tasks WHERE id = :id")
    suspend fun deleteById(id: Long): Int
}

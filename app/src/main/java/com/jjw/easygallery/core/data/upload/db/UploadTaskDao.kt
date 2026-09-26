package com.jjw.easygallery.core.data.upload.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
// TooManyFunctions: 큐 한 표에 대한 질의 모음이라 쪼개면 "어느 DAO 였더라" 를 매번 찾게 된다
@Suppress("TooManyFunctions")
interface UploadTaskDao {

    @Query("SELECT * FROM upload_tasks ORDER BY createdAt, id")
    fun observeAll(): Flow<List<UploadTaskEntity>>

    /** RUNNING 도 포함 — 프로세스가 죽어 남은 항목을 이어서 처리한다. */
    @Query("SELECT * FROM upload_tasks WHERE state IN ('PENDING', 'RUNNING') ORDER BY createdAt, id LIMIT 1")
    suspend fun nextUnfinished(): UploadTaskEntity?

    @Query("SELECT * FROM upload_tasks WHERE state = 'PENDING' AND id NOT IN (:skip) ORDER BY createdAt, id LIMIT 1")
    suspend fun nextPendingExcluding(skip: List<Long>): UploadTaskEntity?

    /**
     * 다음 항목을 **가져오면서 RUNNING 으로 표시**한다. 병렬 업로드에서 두 코루틴이 같은 항목을
     * 집는 것을 막는다 — 트랜잭션이라 조회와 표시 사이에 끼어들 수 없다.
     */
    @Transaction
    suspend fun claimNext(now: Long, skip: List<Long>): UploadTaskEntity? {
        // NOT IN () 는 SQL 오류라 비어 있으면 있을 수 없는 id 를 넣는다
        val task = nextPendingExcluding(skip.ifEmpty { listOf(-1L) }) ?: return null
        updateState(task.id, "RUNNING", task.attemptCount, now)
        return task
    }

    /**
     * 워커가 시작할 때 RUNNING 을 PENDING 으로 되돌린다. 앱이 죽어 RUNNING 인 채 남은 항목은
     * [claimNext] 가 PENDING 만 보므로 그대로 두면 영원히 집히지 않는다.
     */
    @Query(
        "UPDATE upload_tasks SET state = 'PENDING', attemptCount = attemptCount + 1, updatedAt = :now " +
            "WHERE state = 'RUNNING'",
    )
    suspend fun releaseRunning(now: Long)

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
            "errorMessage = NULL, errorReason = NULL, updatedAt = :now WHERE id = :id",
    )
    suspend fun markCompleted(id: Long, fileId: String, now: Long)

    @Query(
        "UPDATE upload_tasks SET state = 'FAILED', errorMessage = :message, errorReason = :reason, " +
            "updatedAt = :now WHERE id = :id",
    )
    suspend fun markFailed(id: Long, message: String?, reason: String?, now: Long)

    /**
     * 끝나지 않은 항목을 모두 같은 이유로 접는다. 용량 초과처럼 **다시 해도 소용없는** 이유가
     * 나왔을 때 쓴다 — 그대로 두면 화면에는 "대기 중"·"업로드 중" 으로 남아 진행 중인 것처럼
     * 보이고, 하나씩 올려보면 같은 실패가 수백 번 쌓인다.
     *
     * `RUNNING` 까지 포함하는 이유: 멈추는 순간 코루틴이 집어 든 항목이 그 상태로 남는다.
     * **모든 코루틴이 끝난 뒤**에 불러야 진행 중인 것을 잘못 접지 않는다.
     */
    @Query(
        "UPDATE upload_tasks SET state = 'FAILED', errorMessage = :message, errorReason = :reason, " +
            "updatedAt = :now WHERE state IN ('PENDING', 'RUNNING')",
    )
    suspend fun failAllUnfinished(message: String?, reason: String?, now: Long)

    @Query("SELECT COUNT(*) FROM upload_tasks WHERE state IN ('PENDING', 'RUNNING')")
    suspend fun countUnfinishedNow(): Int

    /** [accountId] 로 가는 것 중 끝나지 않은 개수. "다른 계정 업로드" 가 끝났는지 볼 때 쓴다 */
    @Query("SELECT COUNT(*) FROM upload_tasks WHERE accountId = :accountId AND state IN ('PENDING', 'RUNNING')")
    suspend fun countUnfinishedFor(accountId: String): Int

    @Query("SELECT COUNT(*) FROM upload_tasks WHERE accountId = :accountId AND state = 'COMPLETED'")
    suspend fun countCompletedFor(accountId: String): Int

    @Query(
        "UPDATE upload_tasks SET state = 'PENDING', errorMessage = NULL, errorReason = NULL, " +
            "attemptCount = 0, updatedAt = :now " +
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

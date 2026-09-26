package com.jjw.easygallery.core.data.upload

import android.net.Uri
import com.jjw.easygallery.core.data.upload.db.UploadTaskDao
import com.jjw.easygallery.core.data.upload.db.UploadTaskEntity
import com.jjw.easygallery.core.domain.model.DriveFolder
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.domain.model.UploadState
import com.jjw.easygallery.core.domain.model.UploadSummary
import com.jjw.easygallery.core.domain.model.UploadTask
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
// TooManyFunctions: 큐 한 줄에 대한 상태 전이가 각각 하나씩이라 항목이 늘면 함수도 는다
@Suppress("TooManyFunctions")
class UploadQueueRepository @Inject constructor(
    private val dao: UploadTaskDao,
) {
    private fun clock(): Long = System.currentTimeMillis()

    fun observeTasks(): Flow<List<UploadTask>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeSummary(): Flow<UploadSummary> = observeTasks().map { tasks ->
        val failures = tasks.filter { it.state == UploadState.FAILED }
        UploadSummary(
            total = tasks.size,
            active = tasks.count { it.isActive },
            completed = tasks.count { it.state == UploadState.COMPLETED },
            failed = failures.size,
            // 하나라도 이유가 다르면 배너가 거짓말이 된다 — 전부 같을 때만 싣는다
            failureReason = failures.mapTo(HashSet()) { it.errorReason }.singleOrNull(),
            current = tasks.firstOrNull { it.state == UploadState.RUNNING }
                ?: tasks.firstOrNull { it.state == UploadState.PENDING },
        )
    }

    /** 이미 대기·진행 중인 미디어는 건너뛴다. 반환값은 실제로 추가된 개수. */
    /**
     * @param alreadyUploaded 이 계정에 이미 올린 미디어. 큐에 넣지 않는다 —
     *   넣으면 Drive 에 같은 파일이 한 벌 더 생긴다(같은 이름으로 공존한다).
     */
    suspend fun enqueue(
        items: List<MediaItem>,
        folder: DriveFolder?,
        accountId: String? = null,
        alreadyUploaded: Set<Long> = emptySet(),
    ): Int {
        val active = dao.unfinishedMediaIds().toHashSet() + alreadyUploaded
        val now = clock()
        val entities = items.filter { it.id !in active }.map { item ->
            UploadTaskEntity(
                mediaId = item.id,
                uri = item.uri.toString(),
                displayName = item.displayName,
                mimeType = item.mimeType,
                sizeBytes = item.sizeBytes,
                width = item.width,
                height = item.height,
                folderId = folder?.id,
                folderName = folder?.name,
                accountId = accountId,
                createdAt = now,
                updatedAt = now,
            )
        }
        if (entities.isEmpty()) return 0
        return dao.insertAll(entities).size
    }

    suspend fun nextUnfinished(): UploadTask? = dao.nextUnfinished()?.toDomain()

    /**
     * 다음 항목을 집으면서 RUNNING 으로 표시. 병렬에서 중복 처리를 막는다.
     * [skip] 은 이번 실행에서 미뤄둔 항목 — 바로 다시 집으면 제자리걸음이 된다.
     */
    suspend fun claimNext(skip: List<Long> = emptyList()): UploadTask? = dao.claimNext(clock(), skip)?.toDomain()

    /** 워커 시작 시 죽은 RUNNING 을 되살린다 */
    suspend fun releaseRunning() = dao.releaseRunning(clock())

    /** 상태 무관하게 큐에 있는 ID */
    suspend fun queuedAmong(mediaIds: Collection<Long>): Set<Long> =
        mediaIds.chunked(QUERY_CHUNK).flatMapTo(HashSet()) { dao.queuedAmong(it) }

    suspend fun countUnfinished(): Int = dao.countUnfinished()

    suspend fun markRunning(id: Long, attemptCount: Int) =
        dao.updateState(id, UploadState.RUNNING.name, attemptCount, clock())

    suspend fun markPending(id: Long, attemptCount: Int) =
        dao.updateState(id, UploadState.PENDING.name, attemptCount, clock())

    suspend fun setSession(id: Long, sessionUri: String?) = dao.updateSession(id, sessionUri, clock())

    suspend fun setFolder(id: Long, folder: DriveFolder) = dao.updateFolder(id, folder.id, folder.name, clock())

    suspend fun updateProgress(id: Long, bytesUploaded: Long) = dao.updateProgress(id, bytesUploaded, clock())

    suspend fun complete(id: Long, driveFileId: String) = dao.markCompleted(id, driveFileId, clock())

    suspend fun fail(id: Long, message: String?, reason: String? = null) =
        dao.markFailed(id, message, reason, clock())

    /**
     * 끝나지 않은 것을 모두 같은 이유로 실패 처리한다. @return 접힌 개수.
     *
     * Room 의 UPDATE 반환값은 이 경로에서 0 으로 오는 일이 있어(2026-09-26 기기에서 확인)
     * 직접 세고 나서 갱신한다 — 로그가 거짓말을 하면 다음 사람이 "안 돌았다" 고 읽는다.
     */
    suspend fun failAllUnfinished(message: String?, reason: String?): Int {
        val count = dao.countUnfinishedNow()
        dao.failAllUnfinished(message, reason, clock())
        return count
    }

    suspend fun retryFailed(): Int = dao.retryFailed(clock())

    suspend fun deleteCompleted(): Int = dao.deleteCompleted()

    suspend fun deleteUnfinished(): Int = dao.deleteUnfinished()

    suspend fun delete(id: Long) = dao.deleteById(id)

    private companion object {
        const val QUERY_CHUNK = 900
    }

    private fun UploadTaskEntity.toDomain() = UploadTask(
        id = id,
        mediaId = mediaId,
        uri = Uri.parse(uri),
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        folderId = folderId,
        folderName = folderName,
        state = state,
        sessionUri = sessionUri,
        bytesUploaded = bytesUploaded,
        driveFileId = driveFileId,
        errorMessage = errorMessage,
        errorReason = errorReason,
        attemptCount = attemptCount,
        createdAt = createdAt,
        width = width,
        height = height,
        accountId = accountId,
    )
}

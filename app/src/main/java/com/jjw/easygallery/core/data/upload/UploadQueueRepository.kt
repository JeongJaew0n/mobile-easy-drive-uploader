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
class UploadQueueRepository @Inject constructor(
    private val dao: UploadTaskDao,
) {
    private fun clock(): Long = System.currentTimeMillis()

    fun observeTasks(): Flow<List<UploadTask>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeSummary(): Flow<UploadSummary> = observeTasks().map { tasks ->
        UploadSummary(
            total = tasks.size,
            active = tasks.count { it.isActive },
            completed = tasks.count { it.state == UploadState.COMPLETED },
            failed = tasks.count { it.state == UploadState.FAILED },
            current = tasks.firstOrNull { it.state == UploadState.RUNNING }
                ?: tasks.firstOrNull { it.state == UploadState.PENDING },
        )
    }

    /** 이미 대기·진행 중인 미디어는 건너뛴다. 반환값은 실제로 추가된 개수. */
    suspend fun enqueue(items: List<MediaItem>, folder: DriveFolder?): Int {
        val active = dao.unfinishedMediaIds().toHashSet()
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
                createdAt = now,
                updatedAt = now,
            )
        }
        if (entities.isEmpty()) return 0
        return dao.insertAll(entities).size
    }

    suspend fun nextUnfinished(): UploadTask? = dao.nextUnfinished()?.toDomain()

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

    suspend fun fail(id: Long, message: String?) = dao.markFailed(id, message, clock())

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
        attemptCount = attemptCount,
        createdAt = createdAt,
        width = width,
        height = height,
    )
}

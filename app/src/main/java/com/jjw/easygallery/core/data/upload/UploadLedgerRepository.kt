package com.jjw.easygallery.core.data.upload

import com.jjw.easygallery.core.data.upload.db.UploadedMediaDao
import com.jjw.easygallery.core.data.upload.db.UploadedMediaEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Drive 에 올라간 항목의 영구 원장. */
@Singleton
class UploadLedgerRepository @Inject constructor(
    private val dao: UploadedMediaDao,
) {
    suspend fun record(mediaId: Long, driveFileId: String, folderId: String?, accountId: String? = null) {
        dao.upsert(UploadedMediaEntity(mediaId, driveFileId, folderId, System.currentTimeMillis(), accountId))
    }

    /** [mediaIds] 중 이미 올라간 것. SQLite 변수 한도(999) 아래로 잘라 조회한다. */
    suspend fun uploadedAmong(mediaIds: Collection<Long>): Set<Long> =
        mediaIds.chunked(QUERY_CHUNK).flatMapTo(HashSet()) { dao.uploadedAmong(it) }

    fun observeUploadedIds(): Flow<Set<Long>> = dao.observeUploadedIds().map { it.toSet() }

    suspend fun count(): Int = dao.count()

    suspend fun clear() = dao.clear()

    private companion object {
        const val QUERY_CHUNK = 900
    }
}

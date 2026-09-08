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

    /** 모든 계정 합집합(중복 정리처럼 "어디든 백업됨"이 기준일 때) */
    fun observeUploadedIds(): Flow<Set<Long>> = dao.observeUploadedIds().map { it.toSet() }

    /** 특정 계정 기준(갤러리 배지·자동 백업 — `docs/MULTI_CLOUD.md` §5). null = Google Drive */
    fun observeUploadedIds(accountId: String?): Flow<Set<Long>> =
        dao.observeUploadedIdsForAccount(accountId).map { it.toSet() }

    suspend fun uploadedAmong(mediaIds: Collection<Long>, accountId: String?): Set<Long> =
        mediaIds.chunked(QUERY_CHUNK).flatMapTo(HashSet()) { dao.uploadedAmongForAccount(it, accountId) }

    suspend fun count(): Int = dao.count()

    suspend fun clear() = dao.clear()

    private companion object {
        const val QUERY_CHUNK = 900
    }
}

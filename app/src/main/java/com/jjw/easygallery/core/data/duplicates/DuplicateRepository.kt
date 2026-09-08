package com.jjw.easygallery.core.data.duplicates

import com.jjw.easygallery.core.data.media.MediaFilter
import com.jjw.easygallery.core.data.media.MediaRepository
import com.jjw.easygallery.core.data.upload.UploadLedgerRepository
import com.jjw.easygallery.core.data.upload.db.MediaHashDao
import com.jjw.easygallery.core.data.upload.db.MediaHashEntity
import com.jjw.easygallery.core.domain.model.DuplicateGroup
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.domain.model.findDuplicateGroups
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 완전 중복 탐지. 크기가 같은 파일이 2개 이상일 때만 해시를 계산하고(대부분 크기가 유일), 결과를 캐시한다.
 * 유사 사진(퍼셉추얼 해시)은 다루지 않는다 — 오탐으로 다른 사진이 삭제될 위험이 있어 의도적으로 제외.
 */
@Singleton
class DuplicateRepository @Inject constructor(
    private val media: MediaRepository,
    private val hashDao: MediaHashDao,
    private val hasher: MediaHasher,
    private val ledger: UploadLedgerRepository,
) {
    data class ScanResult(val candidates: Int, val hashed: Int, val failed: Int)

    /** 현재 갤러리 항목 + 해시 캐시 + 백업 원장으로 그룹을 만든다. 셋 중 무엇이 바뀌어도 갱신 */
    fun observeGroups(): Flow<List<DuplicateGroup>> = combine(
        media.observeMedia(MediaFilter.All),
        hashDao.observeAll(),
        ledger.observeUploadedIds(),
    ) { items, hashes, uploaded ->
        val valid = hashes.filter { h -> items.any { it.id == h.mediaId } }.associate { it.mediaId to it.sha256 }
        findDuplicateGroups(items, valid, uploaded)
    }

    /**
     * 후보(크기 충돌)만 해시한다. 캐시가 크기·수정 시각까지 일치하면 건너뛴다.
     * [onProgress] 는 (완료, 전체) — 워커가 알림·진행률에 쓴다.
     */
    suspend fun scan(onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }): ScanResult {
        val items = media.observeMedia(MediaFilter.All).first()
        val candidates = items
            .filter { it.sizeBytes > 0 }
            .groupBy { it.sizeBytes }
            .values
            .filter { it.size > 1 }
            .flatten()
        val cached = hashDao.getAll().associateBy { it.mediaId }
        val toHash = candidates.filter { item ->
            val c = cached[item.id]
            c == null || c.sizeBytes != item.sizeBytes || c.dateModifiedSeconds != item.dateModifiedSeconds
        }
        pruneStale(cached.keys, items)

        var failed = 0
        toHash.forEachIndexed { index, item ->
            try {
                val sha = hasher.sha256(item.uri)
                hashDao.upsert(
                    MediaHashEntity(item.id, item.sizeBytes, item.dateModifiedSeconds, sha, System.currentTimeMillis()),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                failed++
                Timber.w(e, "hash failed: %s", item.displayName)
            } catch (e: SecurityException) {
                failed++
                Timber.w(e, "hash denied: %s", item.displayName)
            }
            onProgress(index + 1, toHash.size)
        }
        Timber.i("duplicate scan: candidates=%d hashed=%d failed=%d", candidates.size, toHash.size, failed)
        return ScanResult(candidates.size, toHash.size, failed)
    }

    private suspend fun pruneStale(cachedIds: Set<Long>, items: List<MediaItem>) {
        val live = items.mapTo(HashSet()) { it.id }
        val stale = cachedIds.filterNot { it in live }
        stale.chunked(DELETE_CHUNK).forEach { hashDao.deleteByIds(it) }
    }

    private companion object {
        const val DELETE_CHUNK = 900
    }
}

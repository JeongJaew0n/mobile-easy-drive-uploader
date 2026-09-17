package com.jjw.easygallery.core.data.autotag

import com.jjw.easygallery.core.data.media.MediaFilter
import com.jjw.easygallery.core.data.media.MediaRepository
import com.jjw.easygallery.core.data.upload.db.AutoTagCount
import com.jjw.easygallery.core.data.upload.db.AutoTagDao
import com.jjw.easygallery.core.data.upload.db.AutoTagEntity
import com.jjw.easygallery.core.data.upload.db.AutoTagScanEntity
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.domain.model.MediaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * 사진에 기계 라벨을 붙여 모아 둔다(`docs/AUTO_TAGGING.md`).
 * 사용자 카테고리와 별도 표라, 여기서 무엇을 해도 손으로 붙인 카테고리는 바뀌지 않는다.
 */
@Singleton
class AutoTagRepository @Inject constructor(
    private val media: MediaRepository,
    private val dao: AutoTagDao,
    // 훑을 때마다 새로 만들고 끝나면 닫는다 — 스코프가 없어야 한다(ImageLabeler 주석 참고)
    private val labelerProvider: Provider<ImageLabeler>,
) {
    // 즉시 훑기와 매일 훑기는 WorkManager 고유 작업 이름이 달라 겹칠 수 있다.
    // 겹치면 같은 사진을 두 번 디코딩하며 메모리·CPU 만 두 배로 쓴다.
    private val scanLock = Mutex()

    data class ScanResult(
        val scanned: Int,
        val tagged: Int,
        val failed: Int,
        /** 계속 실패해 더 시도하지 않기로 한 사진 수 */
        val givenUp: Int = 0,
        val modelUnavailable: Boolean = false,
    )

    fun observeLabelCounts(): Flow<List<AutoTagCount>> = dao.observeCounts()

    fun observeMediaIds(label: String): Flow<List<Long>> = dao.observeMediaIds(label)

    fun observeScannedCount(): Flow<Int> = dao.observeScannedCount()

    suspend fun mediaIdsOf(label: String): List<Long> = dao.mediaIdsOf(label)

    suspend fun clearAll() = dao.clearAll()

    /**
     * 아직 분석하지 않았거나 파일이 바뀐 **사진**만 훑는다. [onProgress] 는 (완료, 전체).
     * 모델이 아직 없으면 즉시 멈춘다 — 계속 돌아도 전부 실패한다.
     */
    suspend fun scan(onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }): ScanResult =
        scanLock.withLock { scanLocked(onProgress) }

    private suspend fun scanLocked(onProgress: suspend (done: Int, total: Int) -> Unit): ScanResult {
        val items = media.observeMedia(MediaFilter.All).first().filter { it.type == MediaType.IMAGE }
        val cached = dao.allScans().associateBy { it.mediaId }
        prune(cached.keys, items)

        val targets = items.filter { shouldAnalyze(it, cached[it.id]) }
        val givenUp = items.count { item ->
            val seen = cached[item.id]
            seen != null && seen.matches(item) && seen.failureCount >= AutoTagScanEntity.MAX_FAILURES
        }
        if (targets.isEmpty()) {
            onProgress(0, 0)
            return ScanResult(scanned = 0, tagged = 0, failed = 0, givenUp = givenUp)
        }

        var tagged = 0
        var failed = 0
        val labeler = labelerProvider.get()
        try {
            targets.forEachIndexed { index, item ->
                when (val outcome = labelOne(labeler, item, cached[item.id])) {
                    Outcome.ModelUnavailable ->
                        return ScanResult(index, tagged, failed, givenUp, modelUnavailable = true)
                    Outcome.Failed -> failed++
                    is Outcome.Done -> if (outcome.hasLabels) tagged++
                }
                onProgress(index + 1, targets.size)
            }
        } finally {
            runCatching { labeler.close() }
        }
        return ScanResult(scanned = targets.size, tagged = tagged, failed = failed, givenUp = givenUp)
    }

    private suspend fun labelOne(labeler: ImageLabeler, item: MediaItem, seen: AutoTagScanEntity?): Outcome = try {
        val labels = labeler.label(item.uri)
            .filter { it.confidence >= MIN_CONFIDENCE }
            .take(MAX_LABELS_PER_ITEM)
        val now = System.currentTimeMillis()
        dao.replaceFor(
            mediaId = item.id,
            tags = labels.map { AutoTagEntity(item.id, it.label, it.confidence, now) },
            scan = AutoTagScanEntity(item.id, item.sizeBytes, item.dateModifiedSeconds, now, failureCount = 0),
        )
        Outcome.Done(labels.isNotEmpty())
    } catch (e: CancellationException) {
        throw e
    } catch (e: LabelModelUnavailableException) {
        Timber.i(e, "이미지 인식 모델 준비 중 — 훑기 중단")
        Outcome.ModelUnavailable
    } catch (e: IOException) {
        recordFailure(item, seen, e, "읽기")
        Outcome.Failed
    } catch (e: SecurityException) {
        recordFailure(item, seen, e, "권한")
        Outcome.Failed
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        // 디코딩 실패·모델 오류 등은 그 사진만 건너뛴다
        recordFailure(item, seen, e, "기타")
        Outcome.Failed
    }

    /** 실패 횟수를 올려 둔다. [AutoTagScanEntity.MAX_FAILURES] 에 닿으면 다음부터 건너뛴다 */
    private suspend fun recordFailure(item: MediaItem, seen: AutoTagScanEntity?, e: Exception, kind: String) {
        val count = if (seen != null && seen.matches(item)) seen.failureCount + 1 else 1
        // 파일이 바뀌어 다시 분석하다 실패한 경우, 예전 내용 기준 라벨을 남겨 두면 안 된다.
        // 그 라벨로 카테고리를 만들면 엉뚱한 사진이 딸려 들어간다. 라벨이 없는 편이 틀린 라벨보다 낫다.
        dao.deleteTagsOf(item.id)
        dao.insertScan(
            AutoTagScanEntity(item.id, item.sizeBytes, item.dateModifiedSeconds, System.currentTimeMillis(), count),
        )
        if (count >= AutoTagScanEntity.MAX_FAILURES) {
            Timber.w(e, "라벨링 %d회 실패 — 더 시도하지 않는다: %s", count, item.displayName)
        } else {
            Timber.w(e, "라벨링 실패(%s, %d회): %s", kind, count, item.displayName)
        }
    }

    /** 갤러리에서 사라진 사진의 태그·기록을 지운다 */
    private suspend fun prune(known: Set<Long>, items: List<MediaItem>) {
        val alive = items.mapTo(HashSet()) { it.id }
        val gone = known.filterNot { it in alive }
        dao.prune(gone)
    }

    private sealed interface Outcome {
        data class Done(val hasLabels: Boolean) : Outcome
        data object Failed : Outcome
        data object ModelUnavailable : Outcome
    }

    companion object {
        /** 이보다 낮은 라벨은 버린다(`docs/AUTO_TAGGING.md` §5.2) */
        const val MIN_CONFIDENCE = 0.6f
        const val MAX_LABELS_PER_ITEM = 5

        /** 캐시가 이 사진의 현재 파일과 같은 것을 가리키는지 */
        fun AutoTagScanEntity.matches(item: MediaItem): Boolean =
            sizeBytes == item.sizeBytes && dateModifiedSeconds == item.dateModifiedSeconds

        /**
         * 이 사진을 (다시) 분석해야 하는지. 순수 함수라 테스트로 굳힌다.
         * - 기록이 없거나 파일이 바뀌었으면 분석한다(실패 횟수도 다시 센다).
         * - 성공 기록(`failureCount == 0`)이면 건너뛴다.
         * - 실패가 [AutoTagScanEntity.MAX_FAILURES] 미만이면 다시 시도하고, 이상이면 포기한다.
         */
        fun shouldAnalyze(item: MediaItem, seen: AutoTagScanEntity?): Boolean {
            if (seen == null || !seen.matches(item)) return true
            return seen.failureCount in 1 until AutoTagScanEntity.MAX_FAILURES
        }
    }
}

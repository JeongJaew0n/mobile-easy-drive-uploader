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
    // 훑을 때만 만들고 끝나면 닫는다 — 라벨러는 자원을 붙잡고 있다
    private val labelerProvider: Provider<ImageLabeler>,
) {
    data class ScanResult(val scanned: Int, val tagged: Int, val failed: Int, val modelUnavailable: Boolean = false)

    fun observeLabelCounts(): Flow<List<AutoTagCount>> = dao.observeCounts()

    fun observeMediaIds(label: String): Flow<List<Long>> = dao.observeMediaIds(label)

    fun observeScannedCount(): Flow<Int> = dao.observeScannedCount()

    suspend fun mediaIdsOf(label: String): List<Long> = dao.mediaIdsOf(label)

    suspend fun clearAll() = dao.clearAll()

    /**
     * 아직 분석하지 않았거나 파일이 바뀐 **사진**만 훑는다. [onProgress] 는 (완료, 전체).
     * 모델이 아직 없으면 즉시 멈춘다 — 계속 돌아도 전부 실패한다.
     */
    suspend fun scan(onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }): ScanResult {
        val items = media.observeMedia(MediaFilter.All).first().filter { it.type == MediaType.IMAGE }
        val cached = dao.allScans().associateBy { it.mediaId }
        prune(cached.keys, items)

        val targets = items.filter { item ->
            val seen = cached[item.id]
            seen == null || seen.sizeBytes != item.sizeBytes || seen.dateModifiedSeconds != item.dateModifiedSeconds
        }
        if (targets.isEmpty()) {
            onProgress(0, 0)
            return ScanResult(scanned = 0, tagged = 0, failed = 0)
        }

        var tagged = 0
        var failed = 0
        val labeler = labelerProvider.get()
        try {
            targets.forEachIndexed { index, item ->
                when (val outcome = labelOne(labeler, item)) {
                    Outcome.ModelUnavailable ->
                        return ScanResult(index, tagged, failed, modelUnavailable = true)
                    Outcome.Failed -> failed++
                    is Outcome.Done -> if (outcome.hasLabels) tagged++
                }
                onProgress(index + 1, targets.size)
            }
        } finally {
            runCatching { labeler.close() }
        }
        return ScanResult(scanned = targets.size, tagged = tagged, failed = failed)
    }

    private suspend fun labelOne(labeler: ImageLabeler, item: MediaItem): Outcome = try {
        val labels = labeler.label(item.uri)
            .filter { it.confidence >= MIN_CONFIDENCE }
            .take(MAX_LABELS_PER_ITEM)
        val now = System.currentTimeMillis()
        dao.replaceFor(
            mediaId = item.id,
            tags = labels.map { AutoTagEntity(item.id, it.label, it.confidence, now) },
            scan = AutoTagScanEntity(item.id, item.sizeBytes, item.dateModifiedSeconds, now),
        )
        Outcome.Done(labels.isNotEmpty())
    } catch (e: CancellationException) {
        throw e
    } catch (e: LabelModelUnavailableException) {
        Timber.i(e, "이미지 인식 모델 준비 중 — 훑기 중단")
        Outcome.ModelUnavailable
    } catch (e: IOException) {
        Timber.w(e, "라벨링 실패(읽기): %s", item.displayName)
        Outcome.Failed
    } catch (e: SecurityException) {
        Timber.w(e, "라벨링 실패(권한): %s", item.displayName)
        Outcome.Failed
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        // 디코딩 실패·모델 오류 등은 그 사진만 건너뛴다
        Timber.w(e, "라벨링 실패: %s", item.displayName)
        Outcome.Failed
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
    }
}

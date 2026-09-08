package com.jjw.easygallery.core.data.category

import com.jjw.easygallery.core.data.media.MediaFilter
import com.jjw.easygallery.core.data.media.MediaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * MediaStore 에서 사라진 항목의 카테고리 할당을 지운다(`docs/CATEGORIES.md` §6).
 * - 휴지통 항목은 복원될 수 있으므로 살아 있는 것으로 본다
 * - [fullAccess] 가 false(일부 접근)이거나 목록이 비어 있으면 건너뛴다 — 안 보이는 항목을 고아로 오판해 전부 지우는 사고 방지
 */
class OrphanAssignmentCleaner @Inject constructor(
    private val media: MediaRepository,
    private val categories: CategoryRepository,
) {
    /** 권한이 전체 접근일 때만 MediaStore 를 구독한다(권한 전 조회 금지). 실패해도 화면 흐름을 깨지 않는다 */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope, fullAccess: Flow<Boolean>) {
        scope.launch {
            fullAccess
                .distinctUntilChanged()
                .flatMapLatest { full -> if (full) orphanIds() else flowOf(emptySet()) }
                .debounce(DEBOUNCE_MILLIS)
                .catch { Timber.w(it, "orphan cleanup skipped") }
                .collect { orphans -> if (orphans.isNotEmpty()) categories.removeMedia(orphans) }
        }
    }

    private fun orphanIds(): Flow<Set<Long>> =
        combine(categories.observeAssignments(), liveMediaIds()) { assigned, live ->
            if (live.isEmpty()) emptySet() else assigned.keys - live
        }

    private fun liveMediaIds(): Flow<Set<Long>> {
        val all = media.observeMedia(MediaFilter.All)
        if (!media.supportsTrashAndFavorites) return all.map { list -> list.mapTo(HashSet()) { it.id } }
        return combine(all, media.observeMedia(MediaFilter.Trashed)) { a, t ->
            HashSet<Long>(a.size + t.size).apply {
                a.forEach { add(it.id) }
                t.forEach { add(it.id) }
            }
        }
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 2_000L
    }
}

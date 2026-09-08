package com.jjw.easygallery.feature.duplicates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jjw.easygallery.core.data.duplicates.DuplicateRepository
import com.jjw.easygallery.core.data.duplicates.DuplicateScanScheduler
import com.jjw.easygallery.core.data.duplicates.ScanProgress
import com.jjw.easygallery.core.data.media.MediaAction
import com.jjw.easygallery.core.data.media.MediaActionController
import com.jjw.easygallery.core.data.media.MediaRepository
import com.jjw.easygallery.core.domain.model.DuplicateGroup
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class DuplicatesViewModel @Inject constructor(
    repository: DuplicateRepository,
    private val scheduler: DuplicateScanScheduler,
    private val actionController: MediaActionController,
    mediaRepository: MediaRepository,
) : ViewModel() {

    /** 사용자가 기본 제안에서 바꾼 선택만 기억한다 (id → 제거 대상 여부) */
    private val overrides = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    val actionEvents = actionController.events

    private var latestGroups: List<DuplicateGroup> = emptyList()

    val uiState: StateFlow<DuplicatesUiState> = combine(
        repository.observeGroups(),
        scheduler.observeProgress(),
        overrides,
        actionController.isMutating,
    ) { groups, progress, ov, mutating ->
        latestGroups = groups
        val selected = groups.flatMapTo(HashSet()) { g -> g.removeIds.filter { ov[it] != false } } +
            ov.filterValues { it }.keys.filter { id -> groups.any { g -> g.items.any { it.id == id } } }
        DuplicatesUiState(
            groups = groups,
            selectedIds = selected,
            wastedBytes = groups.sumOf { it.wastedBytes },
            selectedBytes = groups.sumOf { g -> g.items.filter { it.id in selected }.sumOf { it.sizeBytes } },
            scan = progress,
            supportsTrash = mediaRepository.supportsTrashAndFavorites,
            isMutating = mutating,
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), DuplicatesUiState())

    fun startScan() = scheduler.start()

    fun cancelScan() = scheduler.cancel()

    fun toggle(id: Long) {
        val isDefaultRemove = latestGroups.any { g -> id in g.removeIds }
        overrides.update { ov ->
            val current = ov[id] ?: isDefaultRemove
            ov + (id to !current)
        }
    }

    fun resetSelection() {
        overrides.value = emptyMap()
    }

    /** 선택 항목을 휴지통으로(API 30+), 휴지통이 없는 기기는 삭제 동의 */
    fun removeSelected(supportsTrash: Boolean) {
        val ids = uiState.value.selectedIds
        val items = latestGroups.flatMap { it.items }.filter { it.id in ids }.distinctBy { it.id }
        if (items.isEmpty()) return
        val action = if (supportsTrash) MediaAction.Trash(items, trashed = true) else MediaAction.Delete(items)
        actionController.perform(viewModelScope, action)
    }

    fun onConsentResult(granted: Boolean) = actionController.onConsentResult(viewModelScope, granted)

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

data class DuplicatesUiState(
    val groups: List<DuplicateGroup> = emptyList(),
    val selectedIds: Set<Long> = emptySet(),
    val wastedBytes: Long = 0,
    val selectedBytes: Long = 0,
    val scan: ScanProgress = ScanProgress(running = false, done = 0, total = 0),
    val supportsTrash: Boolean = true,
    val isMutating: Boolean = false,
    val isLoading: Boolean = true,
) {
    val duplicateCount: Int get() = groups.sumOf { it.duplicateCount }
}

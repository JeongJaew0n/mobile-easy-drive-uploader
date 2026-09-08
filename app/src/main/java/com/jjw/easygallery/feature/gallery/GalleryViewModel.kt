package com.jjw.easygallery.feature.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jjw.easygallery.core.data.auth.AuthException
import com.jjw.easygallery.core.data.media.MediaAction
import com.jjw.easygallery.core.data.media.MediaActionController
import com.jjw.easygallery.core.data.media.MediaFilter
import com.jjw.easygallery.core.data.media.MediaRepository
import com.jjw.easygallery.core.data.upload.UploadLedgerRepository
import com.jjw.easygallery.core.data.upload.UploadQueueRepository
import com.jjw.easygallery.core.domain.model.Album
import com.jjw.easygallery.core.domain.model.DateRange
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.domain.model.UploadSummary
import com.jjw.easygallery.core.domain.model.albumsFrom
import com.jjw.easygallery.core.domain.model.filterByDate
import com.jjw.easygallery.core.domain.usecase.EnqueueUploadsUseCase
import com.jjw.easygallery.core.domain.usecase.ManageUploadQueueUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
@Suppress("TooGenericExceptionCaught") // UI 경계: 큐 등록 실패는 종류를 가리지 않고 메시지로 보여준다
class GalleryViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    uploadQueue: UploadQueueRepository,
    uploadLedger: UploadLedgerRepository,
    private val enqueueUploads: EnqueueUploadsUseCase,
    private val manageQueue: ManageUploadQueueUseCase,
    private val actionController: MediaActionController,
) : ViewModel() {

    // null = 아직 권한 상태를 확인하지 않음
    private val permissionStatus = MutableStateFlow<MediaPermissionStatus?>(null)
    private val filter = MutableStateFlow(MediaFilter.All)
    private val dateRange = MutableStateFlow<DateRange?>(null)
    private val notBackedUpOnly = MutableStateFlow(false)
    private val uploadedIds: Flow<Set<Long>> = uploadLedger.observeUploadedIds()

    /** 필터·기간이 바뀔 때마다 증가. 이 값이 바뀐 직후 첫 목록 갱신은 항목 이동 애니메이션을 끈다(수백 개 동시 이동 방지) */
    private var filterVersion = 0

    // -1 로 시작해 최초 목록은 애니메이션 없이 바로 그린다(시작 페이드 제거, ANIMATION_IMPROVEMENT.md §10)
    private var animatedVersion = -1
    private val selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    private val uploadSummary: Flow<UploadSummary> = uploadQueue.observeSummary()
    private val events = Channel<GalleryEvent>(Channel.BUFFERED)
    val eventFlow: Flow<GalleryEvent> = events.receiveAsFlow()

    /** 편집 동의 흐름은 공용 컨트롤러가 담당 */
    val actionEvents = actionController.events

    private var latestItems: List<MediaItem> = emptyList()

    val uiState: StateFlow<GalleryUiState> = combine(permissionStatus, filter) { status, f -> status to f }
        .flatMapLatest { (status, f) -> stateFor(status, f) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = GalleryUiState.Loading,
        )

    /** UI 가 권한을 확인·요청한 결과를 알려준다. 화면 복귀 시마다 호출되어도 안전(StateFlow 중복 제거). */
    fun onPermissionStatusChanged(status: MediaPermissionStatus) {
        permissionStatus.value = status
    }

    fun setFavoritesOnly(enabled: Boolean) {
        clearSelection()
        filterVersion++
        filter.value = if (enabled) MediaFilter.Favorites else MediaFilter.All
    }

    /** Drive 에 아직 올라가지 않은 항목만 보기 */
    fun setNotBackedUpOnly(enabled: Boolean) {
        clearSelection()
        filterVersion++
        notBackedUpOnly.value = enabled
    }

    /** null 이면 기간 제한 없음 */
    fun setDateRange(range: DateRange?) {
        clearSelection()
        filterVersion++
        dateRange.value = range
    }

    fun toggleSelection(id: Long) {
        selectedIds.update { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        selectedIds.value = emptySet()
    }

    /** 드래그 범위 선택 결과를 통째로 반영 */
    fun setSelection(ids: Set<Long>) {
        selectedIds.value = ids
    }

    // ---------- 업로드 ----------

    /** 선택 항목을 업로드 큐에 넣는다. 실제 전송은 WorkManager 가 백그라운드에서 수행. */
    fun uploadSelected() {
        val items = selectedItems()
        if (items.isEmpty()) return
        viewModelScope.launch {
            try {
                val added = enqueueUploads(items)
                clearSelection()
                events.send(GalleryEvent.Enqueued(added = added, skipped = items.size - added))
            } catch (e: AuthException) {
                Timber.w(e, "upload needs sign-in")
                events.send(GalleryEvent.SignInRequired)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "enqueue failed")
                events.send(GalleryEvent.Error(e.message ?: e.toString()))
            }
        }
    }

    fun cancelUploads() {
        viewModelScope.launch { manageQueue.cancelAll() }
    }

    // ---------- 편집 ----------

    fun deleteSelected() = perform(MediaAction.Delete(selectedItems()))

    fun trashSelected() = perform(MediaAction.Trash(selectedItems(), trashed = true))

    /** 선택이 모두 즐겨찾기면 해제, 아니면 전부 즐겨찾기 */
    fun toggleFavoriteSelected() {
        val items = selectedItems()
        perform(MediaAction.Favorite(items, favorite = !items.all { it.isFavorite }))
    }

    fun onConsentResult(granted: Boolean) = actionController.onConsentResult(viewModelScope, granted)

    /** 확장자를 안 적으면 원본 확장자를 유지한다. */
    fun renameSelected(newName: String) {
        val item = selectedItems().singleOrNull() ?: return
        val normalized = normalizeDisplayName(newName, item.extension) ?: return
        if (normalized == item.displayName) return
        perform(MediaAction.Rename(item, normalized))
    }

    fun moveSelected(relativePath: String) = perform(MediaAction.Move(selectedItems(), relativePath))

    private fun perform(action: MediaAction) = actionController.perform(viewModelScope, action)

    private fun selectedItems(): List<MediaItem> = latestItems.filter { it.id in selectedIds.value }

    private fun stateFor(status: MediaPermissionStatus?, filter: MediaFilter): Flow<GalleryUiState> = when (status) {
        null -> flowOf(GalleryUiState.Loading)
        MediaPermissionStatus.Denied -> flowOf(GalleryUiState.PermissionRequired)
        MediaPermissionStatus.Full, MediaPermissionStatus.Partial -> contentFlow(status, filter)
    }

    /** 목록에서만 파생되는 값. 선택이 바뀔 때는 다시 계산하지 않도록 분리했다(6천 장 O(n) 재계산 방지). */
    @Suppress("LongParameterList") // 파생값 묶음 — 한 곳에서만 생성
    private class Catalog(
        val items: List<MediaItem>,
        val sections: List<GallerySection>,
        val albums: List<Album>,
        val byId: Map<Long, MediaItem>,
        val range: DateRange?,
        val uploadedIds: Set<Long>,
        val uploadedCount: Int,
        val notBackedUpOnly: Boolean,
        /** 기간 필터 이전 목록의 날짜별 개수 — 기간 선택 달력용 */
        val dayCounts: Map<LocalDate, Int>,
        val version: Int,
    )

    private fun contentFlow(status: MediaPermissionStatus, filter: MediaFilter): Flow<GalleryUiState> {
        // 기간·백업 필터는 메모리에서 걸러 MediaStore 를 다시 조회하지 않는다.
        // 원장(uploadedIds)은 업로드가 끝날 때만 바뀌므로 여기서 결합해도 선택 토글과 무관하다.
        val catalog = combine(
            mediaRepository.observeMedia(filter),
            dateRange,
            uploadedIds,
            notBackedUpOnly,
        ) { all, range, uploaded, onlyPending ->
            val base = if (onlyPending) all.filter { it.id !in uploaded } else all
            val inRange = base.filterByDate(range)
            val items = inRange
            latestItems = items
            Catalog(
                items = items,
                sections = groupByDate(items),
                albums = albumsFrom(items),
                byId = items.associateBy { it.id },
                range = range,
                uploadedIds = uploaded,
                uploadedCount = inRange.count { it.id in uploaded },
                notBackedUpOnly = onlyPending,
                dayCounts = countByDay(base),
                version = filterVersion,
            )
        }

        return combine(catalog, selectedIds, uploadSummary, actionController.isMutating) {
                c, selected, summary, mutating ->
            // 필터 변경 후 첫 목록은 애니메이션 없이 교체, 그 뒤(삭제·이동 등)부터 animateItem
            val animate = c.version == animatedVersion
            animatedVersion = c.version
            GalleryUiState.Content(
                sections = c.sections,
                itemCount = c.items.size,
                isPartialAccess = status == MediaPermissionStatus.Partial,
                selectedIds = selected,
                upload = summary,
                favoritesOnly = filter == MediaFilter.Favorites,
                dateRange = c.range,
                uploadedIds = c.uploadedIds,
                uploadedCount = c.uploadedCount,
                notBackedUpOnly = c.notBackedUpOnly,
                dayCounts = c.dayCounts,
                albums = c.albums,
                supportsTrashAndFavorites = mediaRepository.supportsTrashAndFavorites,
                selectedAllFavorite = selected.isNotEmpty() && selected.all { c.byId[it]?.isFavorite == true },
                isMutating = mutating,
                animateItemChanges = animate,
            ) as GalleryUiState
        }
            .onStart { emit(GalleryUiState.Loading) }
            .catch { emit(GalleryUiState.Error(it)) }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** 파일 이름 정리: 공백 제거, 경로 구분자 금지, 확장자 없으면 원본 확장자 유지. 비어 있으면 null. */
internal fun normalizeDisplayName(input: String, originalExtension: String): String? {
    val trimmed = input.trim().replace('/', '_')
    if (trimmed.isEmpty() || trimmed == ".") return null
    return if (!trimmed.contains('.') && originalExtension.isNotEmpty()) "$trimmed.$originalExtension" else trimmed
}

sealed interface GalleryUiState {
    data object Loading : GalleryUiState
    data object PermissionRequired : GalleryUiState
    data class Content(
        val sections: List<GallerySection>,
        val itemCount: Int,
        val isPartialAccess: Boolean,
        val selectedIds: Set<Long> = emptySet(),
        val upload: UploadSummary = UploadSummary(),
        val favoritesOnly: Boolean = false,
        val dateRange: DateRange? = null,
        /** Drive 에 올라간 항목 ID (배지 표시용) */
        val uploadedIds: Set<Long> = emptySet(),
        /** 현재 기간·즐겨찾기 조건 안에서 백업된 개수 */
        val uploadedCount: Int = 0,
        val notBackedUpOnly: Boolean = false,
        /** 기간 필터 이전 목록의 날짜별 개수(기간 선택 달력에서 사진 있는 날 표시) */
        val dayCounts: Map<LocalDate, Int> = emptyMap(),
        val albums: List<Album> = emptyList(),
        val supportsTrashAndFavorites: Boolean = true,
        val selectedAllFavorite: Boolean = false,
        val isMutating: Boolean = false,
        /** false 면 그리드가 항목 이동/등장 애니메이션을 생략한다(필터 전환 직후) */
        val animateItemChanges: Boolean = true,
    ) : GalleryUiState {
        val isSelectionMode: Boolean get() = selectedIds.isNotEmpty()
    }
    data class Error(val throwable: Throwable) : GalleryUiState
}

sealed interface GalleryEvent {
    data object SignInRequired : GalleryEvent
    data class Enqueued(val added: Int, val skipped: Int) : GalleryEvent
    data class Error(val message: String) : GalleryEvent
}

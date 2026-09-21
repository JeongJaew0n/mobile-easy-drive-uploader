package com.jjw.easygallery.feature.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jjw.easygallery.core.data.auth.AuthException
import com.jjw.easygallery.core.data.category.CategoryRepository
import com.jjw.easygallery.core.data.hidden.HiddenMediaRepository
import com.jjw.easygallery.core.data.media.MediaAction
import com.jjw.easygallery.core.data.media.MediaActionController
import com.jjw.easygallery.core.data.media.MediaFilter
import com.jjw.easygallery.core.data.media.MediaRepository
import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import com.jjw.easygallery.core.data.upload.UploadLedgerRepository
import com.jjw.easygallery.core.domain.model.Album
import com.jjw.easygallery.core.domain.model.Category
import com.jjw.easygallery.core.domain.model.CategoryAssignments
import com.jjw.easygallery.core.domain.model.CategoryFilter
import com.jjw.easygallery.core.domain.model.DateRange
import com.jjw.easygallery.core.domain.model.MediaDetails
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.domain.model.albumsFrom
import com.jjw.easygallery.core.domain.model.filterByDate
import com.jjw.easygallery.core.domain.usecase.AssignCategoriesUseCase
import com.jjw.easygallery.core.domain.usecase.EnqueueUploadsUseCase
import com.jjw.easygallery.feature.gallery.normalizeDisplayName
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class MediaViewerViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val actionController: MediaActionController,
    private val enqueueUploads: EnqueueUploadsUseCase,
    uploadLedger: UploadLedgerRepository,
    private val categoryRepository: CategoryRepository,
    private val assignCategories: AssignCategoriesUseCase,
    private val hiddenMedia: HiddenMediaRepository,
    prefs: UserPreferencesRepository,
) : ViewModel() {

    private val uploadedIds = prefs.preferences
        .map { it.uploadAccountId }
        .distinctUntilChanged()
        .flatMapLatest { uploadLedger.observeUploadedIds(it) }

    private val filter = MutableStateFlow<MediaFilter?>(null)
    private var dateRange: DateRange? = null
    private var categoryFilter: CategoryFilter? = null
    private var hiddenOnly = false
    private val currentId = MutableStateFlow<Long?>(null)
    private val details = MutableStateFlow<Map<Long, MediaDetails>>(emptyMap())
    private val showInfo = MutableStateFlow(false)

    private val events = Channel<MediaViewerEvent>(Channel.BUFFERED)
    val eventFlow: Flow<MediaViewerEvent> = events.receiveAsFlow()
    val actionEvents = actionController.events

    private var items: List<MediaItem> = emptyList()

    /** 보고 있던 자리. 삭제로 항목이 사라졌을 때 "바로 이전" 을 찾는 기준이 된다 */
    private var lastIndex = 0

    val uiState: StateFlow<MediaViewerUiState> = filter
        .flatMapLatest { f -> if (f == null) flowOf(MediaViewerUiState()) else contentFlow(f) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), MediaViewerUiState())

    /** 진입 시 한 번. 갤러리와 같은 필터로 목록을 다시 관찰해 좌우 스와이프를 지원한다. */
    fun load(
        mediaId: Long,
        favoritesOnly: Boolean,
        range: DateRange? = null,
        category: CategoryFilter? = null,
        hiddenOnly: Boolean = false,
    ) {
        if (filter.value != null) return
        currentId.value = mediaId
        dateRange = range
        categoryFilter = category
        this.hiddenOnly = hiddenOnly
        filter.value = if (favoritesOnly) MediaFilter.Favorites else MediaFilter.All
    }

    fun onPageChanged(index: Int) {
        items.getOrNull(index)?.let { item ->
            currentId.value = item.id
            loadDetailsIfNeeded(item)
        }
    }

    fun toggleInfo() {
        showInfo.value = !showInfo.value
    }

    fun hideInfo() {
        showInfo.value = false
    }

    // ---------- 편집 ----------

    fun toggleFavorite() = withCurrent { item ->
        actionController.perform(viewModelScope, MediaAction.Favorite(listOf(item), favorite = !item.isFavorite))
    }

    fun trash() = withCurrent { item ->
        actionController.perform(viewModelScope, MediaAction.Trash(listOf(item), trashed = true))
    }

    fun delete() = withCurrent { item ->
        actionController.perform(viewModelScope, MediaAction.Delete(listOf(item)))
    }

    /**
     * 이 사진을 숨긴다. 목록에서 빠지면 삭제와 같은 경로로 이전 사진으로 넘어간다.
     * 말없이 사진이 바뀌면 삭제와 구분이 안 되므로 반드시 알린다.
     */
    fun hide() = withCurrent { item ->
        viewModelScope.launch {
            hiddenMedia.hide(listOf(item.id))
            events.send(MediaViewerEvent.Hidden)
        }
    }

    fun rename(newName: String) = withCurrent { item ->
        val normalized = normalizeDisplayName(newName, item.extension) ?: return@withCurrent
        if (normalized == item.displayName) return@withCurrent
        actionController.perform(viewModelScope, MediaAction.Rename(item, normalized))
    }

    fun move(relativePath: String) = withCurrent { item ->
        actionController.perform(viewModelScope, MediaAction.Move(listOf(item), relativePath))
    }

    fun onConsentResult(granted: Boolean) = actionController.onConsentResult(viewModelScope, granted)

    @Suppress("TooGenericExceptionCaught") // UI 경계
    fun upload() = withCurrent { item ->
        viewModelScope.launch {
            try {
                val added = enqueueUploads(listOf(item))
                events.send(MediaViewerEvent.Enqueued(added))
            } catch (e: AuthException) {
                Timber.w(e, "upload needs sign-in")
                events.send(MediaViewerEvent.SignInRequired)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "enqueue failed")
                events.send(MediaViewerEvent.Error(e.message ?: e.toString()))
            }
        }
    }

    private inline fun withCurrent(block: (MediaItem) -> Unit) {
        items.firstOrNull { it.id == currentId.value }?.let(block)
    }

    private fun contentFlow(mediaFilter: MediaFilter): Flow<MediaViewerUiState> = combine(
        filteredMedia(mediaFilter),
        currentId,
        details,
        extras(),
        actionController.isMutating,
    ) { list, id, detailMap, extras, mutating ->
        items = list
        val found = list.indexOfFirst { it.id == id }
        // 지워졌으면 바로 이전 사진으로 간다. 예전에는 current 가 null 이 되어
        // 화면이 통째로 비었고(검은 화면), 인덱스를 맞추는 쪽에도 닿지 못해 복구되지 않았다.
        val index = if (found >= 0) found else previousIndexAfterRemoval(lastIndex, list.size)
        lastIndex = index
        val current = list.getOrNull(index)
        if (current != null) loadDetailsIfNeeded(current)
        val assigned = current?.let { extras.assignments[it.id] }.orEmpty()
        MediaViewerUiState(
            items = list,
            currentIndex = index,
            current = current,
            details = current?.let { detailMap[it.id] },
            albums = albumsFrom(list),
            supportsTrashAndFavorites = mediaRepository.supportsTrashAndFavorites,
            showInfo = extras.showInfo,
            isUploaded = current != null && current.id in extras.uploaded,
            categories = extras.categories,
            assignments = extras.assignments,
            currentCategories = extras.categories.filter { it.id in assigned },
            isMutating = mutating,
            isLoading = false,
        )
    }.onEach { state ->
        // 자리를 옮겼으면 편집 대상도 곧바로 옮긴다. 페이저가 정착하며 알려주기를 기다리면
        // 그 사이의 삭제·즐겨찾기가 사라진 항목을 가리켜 아무 일도 하지 않는다.
        state.current?.let { if (it.id != currentId.value) currentId.value = it.id }
    }.catch { throwable ->
        Timber.e(throwable, "viewer failed")
        emit(MediaViewerUiState(isLoading = false))
    }

    /** 정보 패널·업로드·카테고리처럼 목록과 무관하게 바뀌는 값 묶음(combine 인자 수 제한) */
    private class Extras(
        val showInfo: Boolean,
        val uploaded: Set<Long>,
        val categories: List<Category>,
        val assignments: CategoryAssignments,
    )

    private fun extras(): Flow<Extras> = combine(
        showInfo,
        uploadedIds,
        categoryRepository.observeCategories(),
        categoryRepository.observeAssignments(),
    ) { info, uploaded, categories, assignments -> Extras(info, uploaded, categories, assignments) }

    // ---------- 카테고리 ----------

    suspend fun createCategory(name: String, colorIndex: Int): Result<Category> =
        categoryRepository.create(name, colorIndex)

    /** 현재 항목 하나에 카테고리를 붙이고 뗀다 */
    fun assignCategoriesToCurrent(add: Set<Long>, remove: Set<Long>) = withCurrent { item ->
        viewModelScope.launch { assignCategories(listOf(item.id), add, remove) }
    }

    /**
     * 갤러리와 같은 순서로 거른다: 숨김 → 카테고리 → 기간.
     * 갤러리에서 안 보이는 사진이 스와이프로 나오면 숨김이 아니다.
     */
    private fun filteredMedia(mediaFilter: MediaFilter): Flow<List<MediaItem>> {
        val visible = combine(
            mediaRepository.observeMedia(mediaFilter),
            hiddenMedia.observeHiddenIds(),
        ) { list, hidden ->
            // 숨긴 사진 화면에서 열었으면 반대로 숨긴 것만 본다 — 그래야 좌우 스와이프가 그 목록과 맞는다
            if (hiddenOnly) list.filter { it.id in hidden } else list.filterNot { it.id in hidden }
        }
        val category = categoryFilter ?: return visible.map { it.filterByDate(dateRange) }
        return combine(visible, categoryRepository.observeAssignments()) { list, assignments ->
            list.filter { category.matches(assignments[it.id]) }.filterByDate(dateRange)
        }
    }

    private fun loadDetailsIfNeeded(item: MediaItem) {
        if (item.isVideo || details.value.containsKey(item.id)) return
        viewModelScope.launch {
            val result = mediaRepository.readDetails(item)
            details.value = details.value + (item.id to result)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * 보고 있던 항목이 [removedIndex] 에서 사라졌을 때 갈 자리 — **바로 이전** 사진이다.
 *
 * 앞쪽 항목은 자리가 밀리지 않으므로 지워지기 전 인덱스에서 하나만 빼면 된다.
 * 첫 번째를 지웠으면 이전이 없어 그 자리(= 다음 사진)에 머문다. 목록이 비면 0
 * (화면은 `MediaViewerRoute` 가 닫는다).
 */
internal fun previousIndexAfterRemoval(removedIndex: Int, newSize: Int): Int {
    if (newSize <= 0) return 0
    return (removedIndex - 1).coerceIn(0, newSize - 1)
}

data class MediaViewerUiState(
    val items: List<MediaItem> = emptyList(),
    val currentIndex: Int = 0,
    val current: MediaItem? = null,
    val details: MediaDetails? = null,
    val albums: List<Album> = emptyList(),
    val supportsTrashAndFavorites: Boolean = true,
    val showInfo: Boolean = false,
    val categories: List<Category> = emptyList(),
    val assignments: CategoryAssignments = emptyMap(),
    /** 현재 항목에 붙은 카테고리(sortOrder 순) */
    val currentCategories: List<Category> = emptyList(),
    /** 현재 항목이 Drive 에 올라가 있음 */
    val isUploaded: Boolean = false,
    val isMutating: Boolean = false,
    val isLoading: Boolean = true,
)

sealed interface MediaViewerEvent {
    data class Enqueued(val added: Int) : MediaViewerEvent
    data object SignInRequired : MediaViewerEvent
    data object Hidden : MediaViewerEvent
    data class Error(val message: String) : MediaViewerEvent
}

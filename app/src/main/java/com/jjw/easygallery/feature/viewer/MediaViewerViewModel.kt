package com.jjw.easygallery.feature.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jjw.easygallery.core.data.auth.AuthException
import com.jjw.easygallery.core.data.category.CategoryRepository
import com.jjw.easygallery.core.data.media.MediaAction
import com.jjw.easygallery.core.data.media.MediaActionController
import com.jjw.easygallery.core.data.media.MediaFilter
import com.jjw.easygallery.core.data.media.MediaRepository
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
) : ViewModel() {

    private val uploadedIds = uploadLedger.observeUploadedIds()

    private val filter = MutableStateFlow<MediaFilter?>(null)
    private var dateRange: DateRange? = null
    private var categoryFilter: CategoryFilter? = null
    private val currentId = MutableStateFlow<Long?>(null)
    private val details = MutableStateFlow<Map<Long, MediaDetails>>(emptyMap())
    private val showInfo = MutableStateFlow(false)

    private val events = Channel<MediaViewerEvent>(Channel.BUFFERED)
    val eventFlow: Flow<MediaViewerEvent> = events.receiveAsFlow()
    val actionEvents = actionController.events

    private var items: List<MediaItem> = emptyList()

    val uiState: StateFlow<MediaViewerUiState> = filter
        .flatMapLatest { f -> if (f == null) flowOf(MediaViewerUiState()) else contentFlow(f) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), MediaViewerUiState())

    /** 진입 시 한 번. 갤러리와 같은 필터로 목록을 다시 관찰해 좌우 스와이프를 지원한다. */
    fun load(mediaId: Long, favoritesOnly: Boolean, range: DateRange? = null, category: CategoryFilter? = null) {
        if (filter.value != null) return
        currentId.value = mediaId
        dateRange = range
        categoryFilter = category
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
        val index = list.indexOfFirst { it.id == id }.takeIf { it >= 0 }
        val current = index?.let(list::get)
        if (current != null) loadDetailsIfNeeded(current)
        val assigned = current?.let { extras.assignments[it.id] }.orEmpty()
        MediaViewerUiState(
            items = list,
            // 현재 항목이 삭제되면 같은 자리(다음 항목)를 보여준다
            currentIndex = index ?: currentIndex(list),
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
    }.catch { throwable ->
        Timber.e(throwable, "viewer failed")
        emit(MediaViewerUiState(isLoading = false))
    }

    /** 현재 항목이 목록에서 사라졌을 때 유지할 인덱스 */
    private fun currentIndex(list: List<MediaItem>): Int =
        uiState.value.currentIndex.coerceIn(0, (list.size - 1).coerceAtLeast(0))

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

    /** 갤러리와 같은 순서로 거른다: 카테고리 → 기간 */
    private fun filteredMedia(mediaFilter: MediaFilter): Flow<List<MediaItem>> {
        val base = mediaRepository.observeMedia(mediaFilter)
        val category = categoryFilter ?: return base.map { it.filterByDate(dateRange) }
        return combine(base, categoryRepository.observeAssignments()) { list, assignments ->
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
    data class Error(val message: String) : MediaViewerEvent
}

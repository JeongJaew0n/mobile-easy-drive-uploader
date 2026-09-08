package com.jjw.easygallery.feature.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jjw.easygallery.core.data.auth.AuthException
import com.jjw.easygallery.core.data.media.MediaAction
import com.jjw.easygallery.core.data.media.MediaActionController
import com.jjw.easygallery.core.data.media.MediaFilter
import com.jjw.easygallery.core.data.media.MediaRepository
import com.jjw.easygallery.core.domain.model.Album
import com.jjw.easygallery.core.domain.model.MediaDetails
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.domain.model.albumsFrom
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
) : ViewModel() {

    private val filter = MutableStateFlow<MediaFilter?>(null)
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
    fun load(mediaId: Long, favoritesOnly: Boolean) {
        if (filter.value != null) return
        currentId.value = mediaId
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
        mediaRepository.observeMedia(mediaFilter),
        currentId,
        details,
        showInfo,
        actionController.isMutating,
    ) { list, id, detailMap, info, mutating ->
        items = list
        val index = list.indexOfFirst { it.id == id }.takeIf { it >= 0 }
        val current = index?.let(list::get)
        if (current != null) loadDetailsIfNeeded(current)
        MediaViewerUiState(
            items = list,
            // 현재 항목이 삭제되면 같은 자리(다음 항목)를 보여준다
            currentIndex = index ?: currentIndex(list),
            current = current,
            details = current?.let { detailMap[it.id] },
            albums = albumsFrom(list),
            supportsTrashAndFavorites = mediaRepository.supportsTrashAndFavorites,
            showInfo = info,
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
    val isMutating: Boolean = false,
    val isLoading: Boolean = true,
)

sealed interface MediaViewerEvent {
    data class Enqueued(val added: Int) : MediaViewerEvent
    data object SignInRequired : MediaViewerEvent
    data class Error(val message: String) : MediaViewerEvent
}

package com.jjw.easygallery.feature.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jjw.easygallery.core.data.auth.AuthException
import com.jjw.easygallery.core.data.media.MediaRepository
import com.jjw.easygallery.core.data.upload.UploadQueueRepository
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.domain.model.UploadSummary
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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
@Suppress("TooGenericExceptionCaught") // UI 경계: 큐 등록 실패는 종류를 가리지 않고 메시지로 보여준다
class GalleryViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    uploadQueue: UploadQueueRepository,
    private val enqueueUploads: EnqueueUploadsUseCase,
    private val manageQueue: ManageUploadQueueUseCase,
) : ViewModel() {

    // null = 아직 권한 상태를 확인하지 않음
    private val permissionStatus = MutableStateFlow<MediaPermissionStatus?>(null)
    private val selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    private val uploadSummary: Flow<UploadSummary> = uploadQueue.observeSummary()
    private val events = Channel<GalleryEvent>(Channel.BUFFERED)
    val eventFlow: Flow<GalleryEvent> = events.receiveAsFlow()

    private var latestItems: List<MediaItem> = emptyList()

    val uiState: StateFlow<GalleryUiState> = permissionStatus
        .flatMapLatest { status -> stateFor(status) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = GalleryUiState.Loading,
        )

    /** UI 가 권한을 확인·요청한 결과를 알려준다. 화면 복귀 시마다 호출되어도 안전(StateFlow 중복 제거). */
    fun onPermissionStatusChanged(status: MediaPermissionStatus) {
        permissionStatus.value = status
    }

    fun toggleSelection(id: Long) {
        selectedIds.update { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        selectedIds.value = emptySet()
    }

    /** 선택 항목을 업로드 큐에 넣는다. 실제 전송은 WorkManager 가 백그라운드에서 수행. */
    fun uploadSelected() {
        val items = latestItems.filter { it.id in selectedIds.value }
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

    private fun stateFor(status: MediaPermissionStatus?): Flow<GalleryUiState> = when (status) {
        null -> flowOf(GalleryUiState.Loading)
        MediaPermissionStatus.Denied -> flowOf(GalleryUiState.PermissionRequired)
        MediaPermissionStatus.Full, MediaPermissionStatus.Partial -> contentFlow(status)
    }

    private fun contentFlow(status: MediaPermissionStatus): Flow<GalleryUiState> {
        val media = mediaRepository.observeMedia().onEach { latestItems = it }
        return combine(media, selectedIds, uploadSummary) { items, selected, summary ->
            GalleryUiState.Content(
                sections = groupByDate(items),
                itemCount = items.size,
                isPartialAccess = status == MediaPermissionStatus.Partial,
                selectedIds = selected,
                upload = summary,
            ) as GalleryUiState
        }
            .onStart { emit(GalleryUiState.Loading) }
            .catch { emit(GalleryUiState.Error(it)) }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
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

package com.jjw.easygallery.feature.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jjw.easygallery.core.data.auth.AuthException
import com.jjw.easygallery.core.data.media.MediaRepository
import com.jjw.easygallery.core.data.upload.UploadEvent
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.domain.usecase.UploadMediaUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
@Suppress("TooGenericExceptionCaught") // 업로드 실패는 종류를 가리지 않고 집계해 사용자에게 알린다
class GalleryViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val uploadMedia: UploadMediaUseCase,
) : ViewModel() {

    // null = 아직 권한 상태를 확인하지 않음
    private val permissionStatus = MutableStateFlow<MediaPermissionStatus?>(null)
    private val selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    private val uploadStatus = MutableStateFlow<UploadStatus?>(null)
    private val events = Channel<GalleryEvent>(Channel.BUFFERED)
    val eventFlow: Flow<GalleryEvent> = events.receiveAsFlow()

    private var latestItems: List<MediaItem> = emptyList()
    private var uploadJob: Job? = null

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

    /** 선택한 항목을 순서대로 업로드한다. 진행 중이면 무시. */
    fun uploadSelected() {
        if (uploadJob?.isActive == true) return
        val items = latestItems.filter { it.id in selectedIds.value }
        if (items.isEmpty()) return
        uploadJob = viewModelScope.launch { runUploads(items) }
    }

    fun cancelUpload() {
        uploadJob?.cancel()
        uploadJob = null
        uploadStatus.value = null
    }

    private suspend fun runUploads(items: List<MediaItem>) {
        var failed = 0
        try {
            items.forEachIndexed { index, item ->
                uploadStatus.value = UploadStatus(index + 1, items.size, item.displayName, 0f)
                try {
                    uploadMedia(item).collect { event ->
                        if (event is UploadEvent.Progress) {
                            uploadStatus.update { it?.copy(fraction = event.fraction) }
                        }
                    }
                } catch (e: AuthException) {
                    Timber.w(e, "upload needs sign-in")
                    events.send(GalleryEvent.SignInRequired)
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "upload failed: %s", item.displayName)
                    failed++
                }
            }
            clearSelection()
            events.send(GalleryEvent.UploadFinished(succeeded = items.size - failed, failed = failed))
        } finally {
            uploadStatus.value = null
        }
    }

    private fun stateFor(status: MediaPermissionStatus?): Flow<GalleryUiState> = when (status) {
        null -> flowOf(GalleryUiState.Loading)
        MediaPermissionStatus.Denied -> flowOf(GalleryUiState.PermissionRequired)
        MediaPermissionStatus.Full, MediaPermissionStatus.Partial -> contentFlow(status)
    }

    private fun contentFlow(status: MediaPermissionStatus): Flow<GalleryUiState> {
        val media = mediaRepository.observeMedia().onEach { latestItems = it }
        return combine(media, selectedIds, uploadStatus) { items, selected, upload ->
            GalleryUiState.Content(
                sections = groupByDate(items),
                itemCount = items.size,
                isPartialAccess = status == MediaPermissionStatus.Partial,
                selectedIds = selected,
                upload = upload,
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
        val upload: UploadStatus? = null,
    ) : GalleryUiState {
        val isSelectionMode: Boolean get() = selectedIds.isNotEmpty()
    }
    data class Error(val throwable: Throwable) : GalleryUiState
}

data class UploadStatus(
    val currentIndex: Int,
    val total: Int,
    val currentName: String,
    val fraction: Float,
)

sealed interface GalleryEvent {
    data object SignInRequired : GalleryEvent
    data class UploadFinished(val succeeded: Int, val failed: Int) : GalleryEvent
}

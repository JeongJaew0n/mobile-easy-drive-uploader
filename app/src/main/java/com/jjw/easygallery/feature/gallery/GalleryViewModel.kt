package com.jjw.easygallery.feature.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jjw.easygallery.core.data.media.MediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
) : ViewModel() {

    // null = 아직 권한 상태를 확인하지 않음
    private val permissionStatus = MutableStateFlow<MediaPermissionStatus?>(null)

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

    private fun stateFor(status: MediaPermissionStatus?): Flow<GalleryUiState> = when (status) {
        null -> flowOf(GalleryUiState.Loading)
        MediaPermissionStatus.Denied -> flowOf(GalleryUiState.PermissionRequired)
        MediaPermissionStatus.Full, MediaPermissionStatus.Partial -> mediaRepository.observeMedia()
            .map<_, GalleryUiState> { items ->
                GalleryUiState.Content(
                    sections = groupByDate(items),
                    itemCount = items.size,
                    isPartialAccess = status == MediaPermissionStatus.Partial,
                )
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
    ) : GalleryUiState
    data class Error(val throwable: Throwable) : GalleryUiState
}

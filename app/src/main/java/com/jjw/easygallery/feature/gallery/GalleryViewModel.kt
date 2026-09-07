package com.jjw.easygallery.feature.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jjw.easygallery.core.data.media.MediaRepository
import com.jjw.easygallery.core.domain.model.MediaItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class GalleryViewModel @Inject constructor(
    mediaRepository: MediaRepository,
) : ViewModel() {

    val uiState: StateFlow<GalleryUiState> = mediaRepository.observeMedia()
        .map<List<MediaItem>, GalleryUiState> { GalleryUiState.Success(it) }
        .catch { emit(GalleryUiState.Error(it)) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = GalleryUiState.Loading,
        )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

sealed interface GalleryUiState {
    data object Loading : GalleryUiState
    data class Success(val items: List<MediaItem>) : GalleryUiState
    data class Error(val throwable: Throwable) : GalleryUiState
}

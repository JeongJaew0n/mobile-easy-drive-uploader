package com.jjw.easygallery.feature.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jjw.easygallery.core.data.media.MediaAction
import com.jjw.easygallery.core.data.media.MediaActionController
import com.jjw.easygallery.core.data.media.MediaFilter
import com.jjw.easygallery.core.data.media.MediaRepository
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.feature.gallery.GallerySection
import com.jjw.easygallery.feature.gallery.groupByDate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class TrashViewModel @Inject constructor(
    mediaRepository: MediaRepository,
    private val actionController: MediaActionController,
) : ViewModel() {

    private val selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val actionEvents = actionController.events

    private var latestItems: List<MediaItem> = emptyList()

    val uiState: StateFlow<TrashUiState> = combine(
        mediaRepository.observeMedia(MediaFilter.Trashed).onEach { latestItems = it },
        selectedIds,
        actionController.isMutating,
    ) { items, selected, mutating ->
        TrashUiState(
            sections = groupByDate(items),
            itemCount = items.size,
            selectedIds = selected,
            isLoading = false,
            isMutating = mutating,
        )
    }
        .catch { emit(TrashUiState(isLoading = false, error = it.message ?: it.toString())) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), TrashUiState())

    fun toggleSelection(id: Long) = selectedIds.update { if (id in it) it - id else it + id }

    fun clearSelection() {
        selectedIds.value = emptySet()
    }

    fun setSelection(ids: Set<Long>) {
        selectedIds.value = ids
    }

    fun restoreSelected() = perform(MediaAction.Trash(selectedItems(), trashed = false))

    fun deleteSelected() = perform(MediaAction.Delete(selectedItems()))

    fun emptyTrash() = perform(MediaAction.Delete(latestItems))

    fun onConsentResult(granted: Boolean) = actionController.onConsentResult(viewModelScope, granted)

    private fun perform(action: MediaAction) = actionController.perform(viewModelScope, action)

    private fun selectedItems() = latestItems.filter { it.id in selectedIds.value }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

data class TrashUiState(
    val sections: List<GallerySection> = emptyList(),
    val itemCount: Int = 0,
    val selectedIds: Set<Long> = emptySet(),
    val isLoading: Boolean = true,
    val isMutating: Boolean = false,
    val error: String? = null,
) {
    val isSelectionMode: Boolean get() = selectedIds.isNotEmpty()
}

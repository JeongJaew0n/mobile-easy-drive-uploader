package com.jjw.easygallery.feature.trash

import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jjw.easygallery.core.data.media.ActionOutcome
import com.jjw.easygallery.core.data.media.MediaAction
import com.jjw.easygallery.core.data.media.MediaActionRunner
import com.jjw.easygallery.core.data.media.MediaFilter
import com.jjw.easygallery.core.data.media.MediaRepository
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.feature.gallery.GallerySection
import com.jjw.easygallery.feature.gallery.groupByDate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
@Suppress("TooGenericExceptionCaught") // UI 경계
class TrashViewModel @Inject constructor(
    mediaRepository: MediaRepository,
    private val actionRunner: MediaActionRunner,
) : ViewModel() {

    private val selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    private val isMutating = MutableStateFlow(false)
    private val events = Channel<TrashEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    private var latestItems: List<MediaItem> = emptyList()
    private var pendingAction: MediaAction? = null

    val uiState: StateFlow<TrashUiState> = combine(
        mediaRepository.observeMedia(MediaFilter.Trashed).onEach { latestItems = it },
        selectedIds,
        isMutating,
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

    fun restoreSelected() = perform(MediaAction.Trash(selectedItems(), trashed = false))

    fun deleteSelected() = perform(MediaAction.Delete(selectedItems()))

    fun emptyTrash() = perform(MediaAction.Delete(latestItems))

    fun onConsentResult(granted: Boolean) {
        val action = pendingAction ?: return
        pendingAction = null
        if (!granted) {
            isMutating.value = false
            viewModelScope.launch { events.send(TrashEvent.ActionCancelled) }
            return
        }
        viewModelScope.launch { runGuarded { actionRunner.afterConsent(action) } }
    }

    private fun perform(action: MediaAction) {
        if (action.items.isEmpty() || isMutating.value) return
        viewModelScope.launch { runGuarded { actionRunner.run(action) } }
    }

    private suspend fun runGuarded(block: suspend () -> ActionOutcome) {
        isMutating.value = true
        try {
            when (val outcome = block()) {
                is ActionOutcome.NeedsConsent -> {
                    pendingAction = outcome.action
                    events.send(TrashEvent.LaunchConsent(outcome.intentSender))
                    return
                }
                is ActionOutcome.Done -> {
                    clearSelection()
                    events.send(TrashEvent.ActionDone(outcome.action, outcome.affected))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "trash action failed")
            events.send(TrashEvent.Error(e.message ?: e.toString()))
        }
        isMutating.value = false
    }

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

sealed interface TrashEvent {
    data class LaunchConsent(val intentSender: IntentSender) : TrashEvent
    data class ActionDone(val action: MediaAction, val affected: Int) : TrashEvent
    data object ActionCancelled : TrashEvent
    data class Error(val message: String) : TrashEvent
}

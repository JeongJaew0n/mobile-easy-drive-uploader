package com.jjw.easygallery.feature.hidden

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jjw.easygallery.core.data.hidden.HiddenMediaRepository
import com.jjw.easygallery.core.data.hidden.HiddenPin
import com.jjw.easygallery.core.data.hidden.HiddenPinRepository
import com.jjw.easygallery.core.data.media.MediaFilter
import com.jjw.easygallery.core.data.media.MediaRepository
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.feature.gallery.GallerySection
import com.jjw.easygallery.feature.gallery.groupByDate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** PIN 을 통과하기 전에는 사진 목록을 만들지도 않는다 */
sealed interface HiddenUiState {
    data object Loading : HiddenUiState

    /** [isSetup] 이면 처음 설정, 아니면 입력. [lockedSeconds] 가 0 보다 크면 기다려야 한다 */
    data class Locked(
        val isSetup: Boolean,
        val failedAttempts: Int = 0,
        val lockedSeconds: Long = 0,
    ) : HiddenUiState

    data class Unlocked(
        val sections: List<GallerySection> = emptyList(),
        val itemCount: Int = 0,
        val selectedIds: Set<Long> = emptySet(),
    ) : HiddenUiState {
        val isSelectionMode: Boolean get() = selectedIds.isNotEmpty()
    }
}

sealed interface HiddenEvent {
    data class Unhidden(val count: Int) : HiddenEvent
    data class Error(val message: String) : HiddenEvent
}

@HiltViewModel
class HiddenViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val hiddenMedia: HiddenMediaRepository,
    private val pinRepository: HiddenPinRepository,
) : ViewModel() {

    private val unlocked = MutableStateFlow(false)
    private val selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    private val events = Channel<HiddenEvent>(Channel.BUFFERED)
    val eventFlow: Flow<HiddenEvent> = events.receiveAsFlow()

    private var latestItems: List<MediaItem> = emptyList()

    val uiState: StateFlow<HiddenUiState> = combine(
        unlocked,
        pinRepository.observeGate(),
        mediaRepository.observeMedia(MediaFilter.All),
        hiddenMedia.observeHiddenIds(),
        selectedIds,
    ) { isUnlocked, gate, all, hiddenIds, selected ->
        if (!isUnlocked) {
            HiddenUiState.Locked(
                isSetup = !gate.isSet,
                failedAttempts = gate.failedAttempts,
                lockedSeconds = (gate.remainingMillis + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND,
            )
        } else {
            val items = all.filter { it.id in hiddenIds }
            latestItems = items
            // 영구 삭제된 사진의 기록이 남지 않게 훑을 때 정리한다
            pruneGone(all, hiddenIds)
            HiddenUiState.Unlocked(
                sections = groupByDate(items),
                itemCount = items.size,
                selectedIds = selected.intersect(items.mapTo(HashSet()) { it.id }),
            )
        }
    }
        .catch { emit(HiddenUiState.Locked(isSetup = false)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), HiddenUiState.Loading)

    /** 처음 설정. 형식이 맞고 두 번 입력이 같아야 한다 */
    fun setPin(pin: String, confirm: String, onResult: (SetPinResult) -> Unit) {
        if (!HiddenPin.isValidFormat(pin)) {
            onResult(SetPinResult.BadFormat)
            return
        }
        if (pin != confirm) {
            onResult(SetPinResult.Mismatch)
            return
        }
        viewModelScope.launch {
            pinRepository.set(pin)
            unlocked.value = true
            onResult(SetPinResult.Ok)
        }
    }

    fun verify(pin: String, onWrong: () -> Unit) {
        viewModelScope.launch {
            if (pinRepository.verify(pin)) unlocked.value = true else onWrong()
        }
    }

    /** 화면을 벗어나면 다시 잠근다 — 뒤로 갔다 오면 또 물어야 숨김이다 */
    fun lock() {
        unlocked.value = false
        selectedIds.value = emptySet()
    }

    fun toggleSelection(id: Long) = selectedIds.update { if (id in it) it - id else it + id }

    fun setSelection(ids: Set<Long>) {
        selectedIds.value = ids
    }

    fun clearSelection() {
        selectedIds.value = emptySet()
    }

    fun unhideSelected() {
        val ids = selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            hiddenMedia.unhide(ids)
            selectedIds.value = emptySet()
            events.send(HiddenEvent.Unhidden(ids.size))
        }
    }

    private suspend fun pruneGone(all: List<MediaItem>, hiddenIds: Set<Long>) {
        val alive = all.mapTo(HashSet()) { it.id }
        if (hiddenIds.any { it !in alive }) hiddenMedia.prune(alive)
    }

    enum class SetPinResult { Ok, BadFormat, Mismatch }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val MILLIS_PER_SECOND = 1_000L
    }
}

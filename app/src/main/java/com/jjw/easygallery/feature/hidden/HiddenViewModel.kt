package com.jjw.easygallery.feature.hidden

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jjw.easygallery.core.data.hidden.HiddenMediaRepository
import com.jjw.easygallery.core.data.hidden.HiddenPin
import com.jjw.easygallery.core.data.hidden.HiddenPinRepository
import com.jjw.easygallery.core.data.hidden.VerifyResult
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

    /**
     * [isSetup] 이면 처음 설정, 아니면 입력.
     * [lockedUntilMillis] 는 절대 시각이다 — 남은 초는 화면이 직접 센다.
     */
    data class Locked(
        val isSetup: Boolean,
        val failedAttempts: Int = 0,
        val lockedUntilMillis: Long = 0,
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

    /** 기기 잠금으로 본인 확인을 마쳤다. PIN 을 잊었을 때 새로 정하는 길 */
    private val resetting = MutableStateFlow(false)
    private val selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    private val events = Channel<HiddenEvent>(Channel.BUFFERED)
    val eventFlow: Flow<HiddenEvent> = events.receiveAsFlow()

    private var latestItems: List<MediaItem> = emptyList()

    /** 조회가 실패했을 때 설정/입력 중 무엇을 보여줄지 판단하는 근거 */
    private var lastKnownPinSet = false

    val uiState: StateFlow<HiddenUiState> = combine(
        combine(unlocked, resetting) { u, r -> u to r },
        pinRepository.observeGate(),
        mediaRepository.observeMedia(MediaFilter.All),
        hiddenMedia.observeHiddenIds(),
        selectedIds,
    ) { (isUnlocked, isResetting), gate, all, hiddenIds, selected ->
        lastKnownPinSet = gate.isSet
        if (!isUnlocked) {
            HiddenUiState.Locked(
                // 기기 잠금을 통과했으면 새 PIN 을 정하는 화면으로 바꾼다
                isSetup = !gate.isSet || isResetting,
                failedAttempts = if (isResetting) 0 else gate.failedAttempts,
                lockedUntilMillis = if (isResetting) 0 else gate.lockedUntilMillis,
            )
        } else {
            val items = all.filter { it.id in hiddenIds }
            latestItems = items
            HiddenUiState.Unlocked(
                sections = groupByDate(items),
                itemCount = items.size,
                selectedIds = selected.intersect(items.mapTo(HashSet()) { it.id }),
            )
        }
    }
        // 조회가 실패해도 PIN 미설정자에게 "입력" 화면을 띄우면 안 된다 —
        // 넣을 비밀번호가 없는 채로 갇힌다. 마지막으로 본 설정 여부를 쓴다
        .catch { emit(HiddenUiState.Locked(isSetup = !lastKnownPinSet)) }
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
            resetting.value = false
            unlocked.value = true
            onResult(SetPinResult.Ok)
        }
    }

    /**
     * 기기 잠금으로 본인 확인을 마쳤다. 잠겨 있어도 여기로는 들어올 수 있어야 한다 —
     * 잊어버린 사람이 정작 되찾지 못하면 복구 장치가 아니다.
     */
    fun onDeviceCredentialConfirmed() {
        resetting.value = true
    }

    fun verify(pin: String, onResult: (VerifyResult) -> Unit) {
        viewModelScope.launch {
            val result = pinRepository.verify(pin)
            if (result == VerifyResult.Ok) unlocked.value = true
            onResult(result)
        }
    }

    /** 화면을 벗어나면 다시 잠근다 — 뒤로 갔다 오면 또 물어야 숨김이다 */
    fun lock() {
        unlocked.value = false
        resetting.value = false
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

    enum class SetPinResult { Ok, BadFormat, Mismatch }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

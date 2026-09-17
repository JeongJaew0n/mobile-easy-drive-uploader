package com.jjw.easygallery.feature.autotag

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jjw.easygallery.core.data.autotag.AutoTagProgress
import com.jjw.easygallery.core.data.autotag.AutoTagRepository
import com.jjw.easygallery.core.data.autotag.AutoTagScheduler
import com.jjw.easygallery.core.data.category.CategoryRepository
import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import com.jjw.easygallery.core.domain.usecase.AssignCategoriesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** 화면에 보여줄 라벨 한 줄 */
data class AutoTagLabelRow(val label: String, val count: Int, val hidden: Boolean = false)

data class AutoTagUiState(
    val enabled: Boolean = false,
    val minuteOfDay: Int = 0,
    val lastRunMillis: Long = 0,
    val scannedCount: Int = 0,
    val labels: List<AutoTagLabelRow> = emptyList(),
    val hiddenCount: Int = 0,
    val showHidden: Boolean = false,
    val progress: AutoTagProgress = AutoTagProgress(running = false, done = 0, total = 0),
    val isBusy: Boolean = false,
)

sealed interface AutoTagEvent {
    data class CategoryCreated(val name: String, val count: Int) : AutoTagEvent
    data class Error(val message: String) : AutoTagEvent
}

@HiltViewModel
@Suppress("TooGenericExceptionCaught") // UI 경계: 어떤 실패든 메시지로 보여준다
class AutoTagViewModel @Inject constructor(
    private val repository: AutoTagRepository,
    private val scheduler: AutoTagScheduler,
    private val prefs: UserPreferencesRepository,
    private val categories: CategoryRepository,
    private val assignCategories: AssignCategoriesUseCase,
) : ViewModel() {

    private val showHidden = MutableStateFlow(false)
    private val isBusy = MutableStateFlow(false)

    private val events = Channel<AutoTagEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    val uiState: StateFlow<AutoTagUiState> = combine(
        prefs.preferences,
        repository.observeLabelCounts(),
        repository.observeScannedCount(),
        scheduler.observeProgress(),
        combine(showHidden, isBusy) { s, b -> s to b },
    ) { p, counts, scanned, progress, (show, busy) ->
        val hiddenLabels = p.autoTagHiddenLabels
        val rows = counts
            .filter { show || it.label !in hiddenLabels }
            .map { AutoTagLabelRow(it.label, it.count, it.label in hiddenLabels) }
        AutoTagUiState(
            enabled = p.autoTagEnabled,
            minuteOfDay = p.autoTagMinuteOfDay,
            lastRunMillis = p.autoTagLastRunMillis,
            scannedCount = scanned,
            labels = rows,
            hiddenCount = hiddenLabels.size,
            showHidden = show,
            progress = progress,
            isBusy = busy,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AutoTagUiState())

    fun setEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.setAutoTagEnabled(enabled)
        if (enabled) scheduler.rescheduleDaily() else scheduler.disableDaily()
    }

    fun setTime(hour: Int, minute: Int) = viewModelScope.launch {
        prefs.setAutoTagMinuteOfDay(AutoTagScheduler.minuteOfDay(hour, minute))
        if (prefs.current().autoTagEnabled) scheduler.rescheduleDaily()
    }

    fun scanNow() = scheduler.scanNow()

    fun stopScan() = viewModelScope.launch { scheduler.cancelScan() }

    fun hideLabel(label: String) = viewModelScope.launch {
        prefs.setAutoTagHiddenLabels(prefs.current().autoTagHiddenLabels + label)
    }

    fun unhideLabel(label: String) = viewModelScope.launch {
        prefs.setAutoTagHiddenLabels(prefs.current().autoTagHiddenLabels - label)
    }

    fun toggleShowHidden() {
        showHidden.value = !showHidden.value
    }

    /** 라벨이 붙은 사진을 같은 이름의 카테고리에 넣는다. 한 번 복사하면 끝이고 이후 동기화는 없다 */
    fun copyToCategory(label: String, displayName: String) = runBusy {
        val mediaIds = repository.mediaIdsOf(label)
        if (mediaIds.isEmpty()) return@runBusy
        val existing = categories.observeCategories().first()
            .firstOrNull { it.name.equals(displayName, ignoreCase = true) }
        // 이름이 겹치면 만들기가 실패하므로, 있으면 그 카테고리에 넣는다
        val category = existing ?: categories.create(displayName, nextColorIndex()).getOrElse { error ->
            events.send(AutoTagEvent.Error(error.message ?: error.toString()))
            return@runBusy
        }
        assignCategories(mediaIds, add = setOf(category.id), remove = emptySet())
        events.send(AutoTagEvent.CategoryCreated(category.name, mediaIds.size))
    }

    /** 팔레트를 순환해 색을 고른다 — 자동 생성이라 사용자에게 묻지 않는다 */
    private suspend fun nextColorIndex(): Int =
        categories.observeCategories().first().size % CATEGORY_COLOR_COUNT

    fun clearAll() = runBusy { repository.clearAll() }

    private fun runBusy(block: suspend () -> Unit) {
        if (isBusy.value) return
        viewModelScope.launch {
            isBusy.value = true
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "자동 태그 동작 실패")
                events.send(AutoTagEvent.Error(e.message ?: e.toString()))
            } finally {
                isBusy.value = false
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val CATEGORY_COLOR_COUNT = 8
    }
}
